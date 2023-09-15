/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.client.timeline;

import org.apache.hudi.client.transaction.TransactionManager;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.model.HoodieAvroPayload;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.ActiveAction;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineUtils;
import org.apache.hudi.common.util.ClusteringUtils;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.CompactionUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.metadata.HoodieTableMetadata;
import org.apache.hudi.table.HoodieTable;
import org.apache.hudi.table.action.compact.CompactionTriggerStrategy;
import org.apache.hudi.table.marker.WriteMarkers;
import org.apache.hudi.table.marker.WriteMarkersFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.client.utils.ArchivalUtils.getMinAndMaxInstantsToKeep;
import static org.apache.hudi.common.table.timeline.HoodieTimeline.GREATER_THAN;
import static org.apache.hudi.common.table.timeline.HoodieTimeline.LESSER_THAN;
import static org.apache.hudi.common.table.timeline.HoodieTimeline.LESSER_THAN_OR_EQUALS;
import static org.apache.hudi.common.table.timeline.HoodieTimeline.compareTimestamps;

/**
 * Archiver to bound the growth of files under .hoodie meta path.
 */
public class HoodieTimelineArchiver<T extends HoodieAvroPayload, I, K, O> {

  private static final Logger LOG = LoggerFactory.getLogger(HoodieTimelineArchiver.class);

  private final HoodieWriteConfig config;
  private final int maxInstantsToKeep;
  private final int minInstantsToKeep;
  private final HoodieTable<T, I, K, O> table;
  private final HoodieTableMetaClient metaClient;
  private final TransactionManager txnManager;

  private final LSMTimelineWriter timelineWriter;

  public HoodieTimelineArchiver(HoodieWriteConfig config, HoodieTable<T, I, K, O> table) {
    this.config = config;
    this.table = table;
    this.metaClient = table.getMetaClient();
    this.txnManager = new TransactionManager(config, table.getMetaClient().getFs());
    this.timelineWriter = LSMTimelineWriter.getInstance(config, table);
    Pair<Integer, Integer> minAndMaxInstants = getMinAndMaxInstantsToKeep(table, metaClient);
    this.minInstantsToKeep = minAndMaxInstants.getLeft();
    this.maxInstantsToKeep = minAndMaxInstants.getRight();
  }

  public boolean archiveIfRequired(HoodieEngineContext context) throws IOException {
    return archiveIfRequired(context, false);
  }

  /**
   * Check if commits need to be archived. If yes, archive commits.
   */
  public boolean archiveIfRequired(HoodieEngineContext context, boolean acquireLock) throws IOException {
    try {
      if (acquireLock) {
        // there is no owner or instant time per se for archival.
        txnManager.beginTransaction(Option.empty(), Option.empty());
      }
      // Sort again because the cleaning and rollback instants could break the sequence.
      List<ActiveAction> instantsToArchive = getInstantsToArchive().sorted().collect(Collectors.toList());
      boolean success = true;
      if (!instantsToArchive.isEmpty()) {
        LOG.info("Archiving instants " + instantsToArchive);
        Consumer<Exception> exceptionHandler = e -> {
          if (this.config.isFailOnTimelineArchivingEnabled()) {
            throw new HoodieException(e);
          }
        };
        this.timelineWriter.write(instantsToArchive, Option.of(action -> deleteAnyLeftOverMarkers(context, action)), Option.of(exceptionHandler));
        LOG.info("Deleting archived instants " + instantsToArchive);
        success = deleteArchivedInstants(instantsToArchive, context);
        // triggers compaction and cleaning only after archiving action
        this.timelineWriter.compactAndClean(context);
      } else {
        LOG.info("No Instants to archive");
      }
      return success;
    } finally {
      if (acquireLock) {
        txnManager.endTransaction(Option.empty());
      }
    }
  }

  private Stream<HoodieInstant> getCleanInstantsToArchive() {
    HoodieTimeline cleanAndRollbackTimeline = table.getActiveTimeline()
        .getTimelineOfActions(CollectionUtils.createSet(HoodieTimeline.CLEAN_ACTION, HoodieTimeline.ROLLBACK_ACTION)).filterCompletedInstants();
    return cleanAndRollbackTimeline.getInstantsAsStream()
        .collect(Collectors.groupingBy(HoodieInstant::getAction)).values().stream()
        .map(hoodieInstants -> {
          if (hoodieInstants.size() > this.maxInstantsToKeep) {
            return hoodieInstants.subList(0, hoodieInstants.size() - this.minInstantsToKeep);
          } else {
            return Collections.<HoodieInstant>emptyList();
          }
        }).flatMap(Collection::stream);
  }

  private Stream<HoodieInstant> getCommitInstantsToArchive() throws IOException {
    // TODO (na) : Add a way to return actions associated with a timeline and then merge/unify
    // with logic above to avoid Stream.concat
    HoodieTimeline commitTimeline = table.getCompletedCommitsTimeline();

    // Get the oldest inflight instant and a completed commit before this inflight instant.
    Option<HoodieInstant> oldestPendingInstant = table.getActiveTimeline()
        .getWriteTimeline()
        .filter(instant -> !instant.isCompleted())
        .firstInstant();

    // Oldest commit to retain is the greatest completed commit, that is less than the oldest pending instant.
    // In some cases when inflight is the lowest commit then oldest commit to retain will be equal to oldest
    // inflight commit.
    Option<HoodieInstant> oldestCommitToRetain;
    if (oldestPendingInstant.isPresent()) {
      Option<HoodieInstant> completedCommitBeforeOldestPendingInstant =
          Option.fromJavaOptional(commitTimeline.getReverseOrderedInstants()
              .filter(instant -> HoodieTimeline.compareTimestamps(instant.getTimestamp(),
                  LESSER_THAN, oldestPendingInstant.get().getTimestamp())).findFirst());
      // Check if the completed instant is higher than the oldest inflight instant
      // in that case update the oldestCommitToRetain to oldestInflight commit time.
      if (!completedCommitBeforeOldestPendingInstant.isPresent()) {
        oldestCommitToRetain = oldestPendingInstant;
      } else {
        oldestCommitToRetain = completedCommitBeforeOldestPendingInstant;
      }
    } else {
      oldestCommitToRetain = Option.empty();
    }

    // NOTE: We cannot have any holes in the commit timeline.
    // We cannot archive any commits which are made after the first savepoint present,
    // unless HoodieArchivalConfig#ARCHIVE_BEYOND_SAVEPOINT is enabled.
    Option<HoodieInstant> firstSavepoint = table.getCompletedSavepointTimeline().firstInstant();
    Set<String> savepointTimestamps = table.getSavepointTimestamps();
    if (!commitTimeline.empty() && commitTimeline.countInstants() > maxInstantsToKeep) {
      // For Merge-On-Read table, inline or async compaction is enabled
      // We need to make sure that there are enough delta commits in the active timeline
      // to trigger compaction scheduling, when the trigger strategy of compaction is
      // NUM_COMMITS or NUM_AND_TIME.
      Option<HoodieInstant> oldestInstantToRetainForCompaction =
          (metaClient.getTableType() == HoodieTableType.MERGE_ON_READ
              && (config.getInlineCompactTriggerStrategy() == CompactionTriggerStrategy.NUM_COMMITS
              || config.getInlineCompactTriggerStrategy() == CompactionTriggerStrategy.NUM_AND_TIME))
              ? CompactionUtils.getOldestInstantToRetainForCompaction(
              table.getActiveTimeline(), config.getInlineCompactDeltaCommitMax())
              : Option.empty();

      // The clustering commit instant can not be archived unless we ensure that the replaced files have been cleaned,
      // without the replaced files metadata on the timeline, the fs view would expose duplicates for readers.
      // Meanwhile, when inline or async clustering is enabled, we need to ensure that there is a commit in the active timeline
      // to check whether the file slice generated in pending clustering after archive isn't committed.
      Option<HoodieInstant> oldestInstantToRetainForClustering =
          ClusteringUtils.getOldestInstantToRetainForClustering(table.getActiveTimeline(), table.getMetaClient());

      // Actually do the commits
      Stream<HoodieInstant> instantToArchiveStream = commitTimeline.getInstantsAsStream()
          .filter(s -> {
            if (config.shouldArchiveBeyondSavepoint()) {
              // skip savepoint commits and proceed further
              return !savepointTimestamps.contains(s.getTimestamp());
            } else {
              // if no savepoint present, then don't filter
              // stop at first savepoint commit
              return !(firstSavepoint.isPresent() && compareTimestamps(firstSavepoint.get().getTimestamp(), LESSER_THAN_OR_EQUALS, s.getTimestamp()));
            }
          }).filter(s -> {
            // oldestCommitToRetain is the highest completed commit instant that is less than the oldest inflight instant.
            // By filtering out any commit >= oldestCommitToRetain, we can ensure there are no gaps in the timeline
            // when inflight commits are present.
            return oldestCommitToRetain
                .map(instant -> compareTimestamps(instant.getTimestamp(), GREATER_THAN, s.getTimestamp()))
                .orElse(true);
          }).filter(s ->
              oldestInstantToRetainForCompaction.map(instantToRetain ->
                      compareTimestamps(s.getTimestamp(), LESSER_THAN, instantToRetain.getTimestamp()))
                  .orElse(true)
          ).filter(s ->
              oldestInstantToRetainForClustering.map(instantToRetain ->
                      HoodieTimeline.compareTimestamps(s.getTimestamp(), LESSER_THAN, instantToRetain.getTimestamp()))
                  .orElse(true)
          );
      return instantToArchiveStream.limit(commitTimeline.countInstants() - minInstantsToKeep);
    } else {
      return Stream.empty();
    }
  }

  private Stream<ActiveAction> getInstantsToArchive() throws IOException {
    if (config.isMetaserverEnabled()) {
      return Stream.empty();
    }

    // For archiving and cleaning instants, we need to include intermediate state files if they exist
    HoodieActiveTimeline rawActiveTimeline = new HoodieActiveTimeline(metaClient, false);
    Map<Pair<String, String>, List<HoodieInstant>> groupByTsAction = rawActiveTimeline.getInstantsAsStream()
            .collect(Collectors.groupingBy(i -> Pair.of(i.getTimestamp(),
                    HoodieInstant.getComparableAction(i.getAction()))));

    Stream<HoodieInstant> instants = Stream.concat(getCleanInstantsToArchive(), getCommitInstantsToArchive());

    // If metadata table is enabled, do not archive instants which are more recent than the last compaction on the
    // metadata table.
    if (table.getMetaClient().getTableConfig().isMetadataTableAvailable()) {
      try (HoodieTableMetadata tableMetadata = HoodieTableMetadata.create(table.getContext(), config.getMetadataConfig(), config.getBasePath())) {
        Option<String> latestCompactionTime = tableMetadata.getLatestCompactionTime();
        if (!latestCompactionTime.isPresent()) {
          LOG.info("Not archiving as there is no compaction yet on the metadata table");
          instants = Stream.empty();
        } else {
          LOG.info("Limiting archiving of instants to latest compaction on metadata table at " + latestCompactionTime.get());
          instants = instants.filter(instant -> compareTimestamps(instant.getTimestamp(), LESSER_THAN,
              latestCompactionTime.get()));
        }
      } catch (Exception e) {
        throw new HoodieException("Error limiting instant archival based on metadata table", e);
      }
    }

    if (table.isMetadataTable()) {
      HoodieTableMetaClient dataMetaClient = HoodieTableMetaClient.builder()
          .setBasePath(HoodieTableMetadata.getDatasetBasePath(config.getBasePath()))
          .setConf(metaClient.getHadoopConf())
          .build();
      Option<HoodieInstant> qualifiedEarliestInstant =
          TimelineUtils.getEarliestInstantForMetadataArchival(
              dataMetaClient.getActiveTimeline(), config.shouldArchiveBeyondSavepoint());

      // Do not archive the instants after the earliest commit (COMMIT, DELTA_COMMIT, and
      // REPLACE_COMMIT only, considering non-savepoint commit only if enabling archive
      // beyond savepoint) and the earliest inflight instant (all actions).
      // This is required by metadata table, see HoodieTableMetadataUtil#processRollbackMetadata
      // for details.
      // Note that we cannot blindly use the earliest instant of all actions, because CLEAN and
      // ROLLBACK instants are archived separately apart from commits (check
      // HoodieTimelineArchiver#getCleanInstantsToArchive).  If we do so, a very old completed
      // CLEAN or ROLLBACK instant can block the archive of metadata table timeline and causes
      // the active timeline of metadata table to be extremely long, leading to performance issues
      // for loading the timeline.
      if (qualifiedEarliestInstant.isPresent()) {
        instants = instants.filter(instant ->
            compareTimestamps(
                instant.getTimestamp(),
                HoodieTimeline.LESSER_THAN,
                qualifiedEarliestInstant.get().getTimestamp()));
      }
    }

    return instants.map(hoodieInstant -> {
      List<HoodieInstant> instantsToStream = groupByTsAction.get(Pair.of(hoodieInstant.getTimestamp(),
          HoodieInstant.getComparableAction(hoodieInstant.getAction())));
      return ActiveAction.fromInstants(instantsToStream);
    });
  }

  private boolean deleteArchivedInstants(List<ActiveAction> activeActions, HoodieEngineContext context) {
    LOG.info("Deleting instants " + activeActions);

    List<HoodieInstant> pendingInstants = new ArrayList<>();
    List<HoodieInstant> completedInstants = new ArrayList<>();

    for (ActiveAction activeAction : activeActions) {
      completedInstants.add(activeAction.getCompleted());
      pendingInstants.addAll(activeAction.getPendingInstants());
    }

    context.setJobStatus(this.getClass().getSimpleName(), "Delete archived instants: " + config.getTableName());
    // Delete the metadata files
    // in HoodieInstant.State sequence: requested -> inflight -> completed,
    // this is important because when a COMPLETED metadata file is removed first,
    // other monitors on the timeline(such as the compaction or clustering services) would
    // mistakenly recognize the pending file as a pending operation,
    // then all kinds of weird bugs occur.
    HoodieActiveTimeline activeTimeline = metaClient.getActiveTimeline();
    if (!pendingInstants.isEmpty()) {
      context.foreach(
          pendingInstants,
          instant -> activeTimeline.deleteInstantFileIfExists(instant),
          Math.min(pendingInstants.size(), config.getArchiveDeleteParallelism())
      );
    }
    if (!completedInstants.isEmpty()) {
      context.foreach(
          completedInstants,
          instant -> activeTimeline.deleteInstantFileIfExists(instant),
          Math.min(completedInstants.size(), config.getArchiveDeleteParallelism())
      );
    }

    return true;
  }

  private void deleteAnyLeftOverMarkers(HoodieEngineContext context, ActiveAction activeAction) {
    WriteMarkers writeMarkers = WriteMarkersFactory.get(config.getMarkersType(), table, activeAction.getInstantTime());
    if (writeMarkers.deleteMarkerDir(context, config.getMarkersDeleteParallelism())) {
      LOG.info("Cleaned up left over marker directory for instant :" + activeAction.getCompleted());
    }
  }
}
