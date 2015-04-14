/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spotify.reaper.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;

import com.spotify.reaper.AppContext;
import com.spotify.reaper.ReaperException;
import com.spotify.reaper.cassandra.JmxProxy;
import com.spotify.reaper.cassandra.RepairStatusHandler;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairSegment;
import com.spotify.reaper.core.RepairUnit;

import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.utils.concurrent.SimpleCondition;
import org.joda.time.DateTime;
import org.joda.time.Seconds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

public final class SegmentRunner implements RepairStatusHandler, Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(SegmentRunner.class);
  private static final int MAX_PENDING_COMPACTIONS = 20;

  private final AppContext context;
  private final long segmentId;
  private final Condition condition = new SimpleCondition();
  private final Collection<String> potentialCoordinators;
  private final long timeoutMillis;
  private final double intensity;
  private String clusterName;
  private int commandId;

  // Caching all active SegmentRunners.
  @VisibleForTesting
  public static Map<Long, SegmentRunner> segmentRunners = Maps.newConcurrentMap();

//  private SegmentRunner(AppContext context, long segmentId) {
//    this.context = context;
//    this.segmentId = segmentId;
//  }

  public SegmentRunner(AppContext context, long segmentId, Collection<String> potentialCoordinators,
      long timeoutMillis, double intensity, String clusterName) throws ReaperException {
    this.context = context;
    this.segmentId = segmentId;
    this.potentialCoordinators = potentialCoordinators;
    this.timeoutMillis = timeoutMillis;
    this.intensity = intensity;
    this.clusterName = clusterName;
  }

  @Override
  public void run() {
    final RepairSegment segment = context.storage.getRepairSegment(segmentId).get();
    Thread.currentThread().setName(clusterName + ":" + segment.getRunId() + ":" + segmentId);

    runRepair();
    long delay = intensityBasedDelayMillis(intensity);
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      LOG.warn("Slept shorter than intended delay.");
    }
  }

  public static void postpone(AppContext context, RepairSegment segment) {
    LOG.warn("Postponing segment {}", segment.getId());
    context.storage.updateRepairSegment(segment.with()
                                            .state(RepairSegment.State.NOT_STARTED)
                                            .coordinatorHost(null)
                                            .repairCommandId(null)
                                            .startTime(null)
                                            .failCount(segment.getFailCount() + 1)
                                            .build(segment.getId()));
    segmentRunners.remove(segment.getId());
  }

  public static void abort(AppContext context, RepairSegment segment, JmxProxy jmxConnection) {
    postpone(context, segment);
    LOG.info("Aborting repair on segment with id {} on coordinator {}",
             segment.getId(), segment.getCoordinatorHost());
    jmxConnection.cancelAllRepairs();
  }

  private void runRepair() {
    final RepairSegment segment = context.storage.getRepairSegment(segmentId).get();
    final RepairRun repairRun = context.storage.getRepairRun(segment.getRunId()).get();
    try (JmxProxy coordinator = context.jmxConnectionFactory
        .connectAny(Optional.<RepairStatusHandler>of(this), potentialCoordinators)) {

      if (segmentRunners.containsKey(segmentId)) {
        LOG.error("SegmentRunner already exists for segment with ID: " + segmentId);
        throw new ReaperException("SegmentRunner already exists for segment with ID: " + segmentId);
      }
      segmentRunners.put(segmentId, this);

      RepairUnit repairUnit = context.storage.getRepairUnit(segment.getRepairUnitId()).get();
      String keyspace = repairUnit.getKeyspaceName();

      if (!canRepair(segment, keyspace, coordinator, repairRun)) {
        postpone(segment);
        return;
      }

      synchronized (condition) {
        commandId = coordinator.triggerRepair(segment.getStartToken(), segment.getEndToken(),
                                              keyspace, repairRun.getRepairParallelism(),
                                              repairUnit.getColumnFamilies());

        if (commandId == 0) {
          // From cassandra source in "forceRepairAsync":
          //if (ranges.isEmpty() || Keyspace.open(keyspace).getReplicationStrategy().getReplicationFactor() < 2)
          //  return 0;
          LOG.info("Nothing to repair for keyspace {}", keyspace);
          context.storage.updateRepairSegment(segment.with()
              .coordinatorHost(coordinator.getHost())
              .state(RepairSegment.State.DONE)
              .build(segmentId));
          segmentRunners.remove(segment.getId());
          return;
        }

        LOG.debug("Triggered repair with command id {}", commandId);
        context.storage.updateRepairSegment(segment.with()
                                                .coordinatorHost(coordinator.getHost())
                                                .repairCommandId(commandId)
                                                .build(segmentId));
        String eventMsg = String.format("Triggered repair of segment %d via host %s",
                                        segment.getId(), coordinator.getHost());
        context.storage.updateRepairRun(
            repairRun.with().lastEvent(eventMsg).build(repairRun.getId()));
        LOG.info("Repair for segment {} started, status wait will timeout in {} millis", segmentId,
                 timeoutMillis);
        try {
          condition.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          LOG.warn("Repair command {} on segment {} interrupted", commandId, segmentId);
        } finally {
          RepairSegment resultingSegment = context.storage.getRepairSegment(segmentId).get();
          LOG.info("Repair command {} on segment {} returned with state {}", commandId, segmentId,
                   resultingSegment.getState());
          if (resultingSegment.getState().equals(RepairSegment.State.RUNNING)) {
            LOG.info("Repair command {} on segment {} has been cancelled while running", commandId,
                     segmentId);
            abort(resultingSegment, coordinator);
          } else if (resultingSegment.getState().equals(RepairSegment.State.DONE)) {
            LOG.debug("Repair segment with id '{}' was repaired in {} seconds",
                      resultingSegment.getId(),
                      Seconds.secondsBetween(
                          resultingSegment.getStartTime(),
                          resultingSegment.getEndTime()).getSeconds());
            segmentRunners.remove(resultingSegment.getId());
          }
        }
      }
    } catch (ReaperException e) {
      LOG.warn("Failed to connect to a coordinator node for segment {}", segmentId);
      String msg = String.format("Postponed because couldn't any of the coordinators");
      context.storage.updateRepairRun(repairRun.with().lastEvent(msg).build(repairRun.getId()));
      postpone(segment);
    }
  }

  boolean canRepair(RepairSegment segment, String keyspace, JmxProxy coordinator,
      RepairRun repairRun) {
    Collection<String> allHosts =
        coordinator.tokenRangeToEndpoint(keyspace, segment.getTokenRange());
    for (String hostName : allHosts) {
      LOG.debug("checking host '{}' for pending compactions and other repairs (can repair?)"
          + " Run id '{}'", hostName, segment.getRunId());
      try (JmxProxy hostProxy = context.jmxConnectionFactory.connect(hostName)) {
        int pendingCompactions = hostProxy.getPendingCompactions();
        if (pendingCompactions > MAX_PENDING_COMPACTIONS) {
          LOG.warn("SegmentRunner declined to repair segment {} because of too many pending "
                   + "compactions (> {}) on host \"{}\"", segmentId, MAX_PENDING_COMPACTIONS,
                   hostProxy.getHost());
          String msg = String.format("Postponed due to pending compactions (%d)",
              pendingCompactions);
          context.storage.updateRepairRun(repairRun.with().lastEvent(msg).build(repairRun.getId()));
          return false;
        }
        if (hostProxy.isRepairRunning()) {
          LOG.warn("SegmentRunner declined to repair segment {} because one of the hosts ({}) was "
                   + "already involved in a repair", segmentId, hostProxy.getHost());
          String msg = String.format("Postponed due to affected hosts already doing repairs");
          context.storage.updateRepairRun(repairRun.with().lastEvent(msg).build(repairRun.getId()));
          return false;
        }
      } catch (ReaperException e) {
        LOG.warn("SegmentRunner declined to repair segment {} because one of the hosts ({}) could "
            + "not be connected with", segmentId, hostName);
        String msg = String.format("Postponed due to inability to connect host %s", hostName);
        context.storage.updateRepairRun(repairRun.with().lastEvent(msg).build(repairRun.getId()));
        return false;
      }
    }
    LOG.info("It is ok to repair segment '{}' om repair run with id '{}'",
             segment.getId(), segment.getRunId());
    return true;
  }

  private void postpone(RepairSegment segment) {
    postpone(context, segment);
  }

  private void abort(RepairSegment segment, JmxProxy jmxConnection) {
    abort(context, segment, jmxConnection);
  }

  /**
   * Called when there is an event coming either from JMX or this runner regarding on-going
   * repairs.
   *
   * @param repairNumber repair sequence number, obtained when triggering a repair
   * @param status       new status of the repair
   * @param message      additional information about the repair
   */
  @Override
  public void handle(int repairNumber, ActiveRepairService.Status status, String message) {
    final RepairSegment segment = context.storage.getRepairSegment(segmentId).get();
    Thread.currentThread().setName(clusterName + ":" + segment.getRunId() + ":" + segmentId);

    synchronized (condition) {
      LOG.debug(
          "handle called for repairCommandId {}, outcome {} and message: {}",
          repairNumber, status, message);
      if (repairNumber != commandId) {
        LOG.debug("Handler for command id {} not handling message with number {}",
                  commandId, repairNumber);
        return;
      }

      RepairSegment currentSegment = context.storage.getRepairSegment(segmentId).get();
      // See status explanations at: https://wiki.apache.org/cassandra/RepairAsyncAPI
      switch (status) {
        case STARTED:
          DateTime now = DateTime.now();
          context.storage.updateRepairSegment(currentSegment.with()
                                                  .state(RepairSegment.State.RUNNING)
                                                  .startTime(now)
                                                  .build(segmentId));
          LOG.debug("updated segment {} with state {}", segmentId, RepairSegment.State.RUNNING);
          break;
        case SESSION_SUCCESS:
          LOG.debug("repair session succeeded for segment with id '{}' and repair number '{}'",
              segmentId, repairNumber);
          context.storage.updateRepairSegment(currentSegment.with()
              .state(RepairSegment.State.DONE)
              .endTime(DateTime.now())
              .build(segmentId));
          break;
        case SESSION_FAILED:
          LOG.warn("repair session failed for segment with id '{}' and repair number '{}'",
              segmentId, repairNumber);
          postpone(currentSegment);
          break;
        case FINISHED:
          // This gets called at the end regardless of succeeded or failed sessions.
          LOG.debug("repair session finished for segment with id '{}' and repair number '{}'",
              segmentId, repairNumber);
          condition.signalAll();
          break;
      }
    }
  }

  /**
   * Calculate the delay that should be used before starting the next repair segment.
   *
   * @return the delay in milliseconds.
   */
  long intensityBasedDelayMillis(double intensity) {
    RepairSegment repairSegment = context.storage.getRepairSegment(segmentId).get();
    if (repairSegment.getEndTime() == null && repairSegment.getStartTime() == null) {
      return 0;
    }
    else if (repairSegment.getEndTime() != null && repairSegment.getStartTime() != null) {
      long repairEnd = repairSegment.getEndTime().getMillis();
      long repairStart = repairSegment.getStartTime().getMillis();
      long repairDuration = repairEnd - repairStart;
      long delay = (long) (repairDuration / intensity - repairDuration);
      LOG.debug("Scheduling next runner run() with delay {} ms", delay);
      return delay;
    } else
    {
      LOG.error("Segment {} returned with startTime {} and endTime {}. This should not happen."
              + "Intensity cannot apply, so next run will start immediately.",
          repairSegment.getId(), repairSegment.getStartTime(), repairSegment.getEndTime());
      return 0;
    }
  }

}
