/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.engine.internal;

import com.google.common.base.Predicate;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.codec.CodecService;
import org.elasticsearch.index.deletionpolicy.KeepOnlyLastDeletionPolicy;
import org.elasticsearch.index.deletionpolicy.SnapshotDeletionPolicy;
import org.elasticsearch.index.deletionpolicy.SnapshotIndexCommit;
import org.elasticsearch.index.engine.*;
import org.elasticsearch.index.indexing.ShardIndexingService;
import org.elasticsearch.index.indexing.slowlog.ShardSlowLogIndexingService;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.ParseContext.Document;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.internal.SourceFieldMapper;
import org.elasticsearch.index.mapper.internal.UidFieldMapper;
import org.elasticsearch.index.merge.OnGoingMerge;
import org.elasticsearch.index.merge.policy.LogByteSizeMergePolicyProvider;
import org.elasticsearch.index.merge.policy.MergePolicyProvider;
import org.elasticsearch.index.merge.scheduler.ConcurrentMergeSchedulerProvider;
import org.elasticsearch.index.merge.scheduler.MergeSchedulerProvider;
import org.elasticsearch.index.settings.IndexDynamicSettingsModule;
import org.elasticsearch.index.settings.IndexSettingsService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardUtils;
import org.elasticsearch.index.store.DirectoryService;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.store.distributor.LeastUsedDistributor;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.index.translog.TranslogSizeMatcher;
import org.elasticsearch.index.translog.fs.FsTranslog;
import org.elasticsearch.test.DummyShardLock;
import org.elasticsearch.test.ElasticsearchLuceneTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.carrotsearch.randomizedtesting.RandomizedTest.between;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomBoolean;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomDouble;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomIntBetween;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomLong;
import static org.elasticsearch.common.settings.ImmutableSettings.Builder.EMPTY_SETTINGS;
import static org.elasticsearch.index.engine.Engine.Operation.Origin.PRIMARY;
import static org.elasticsearch.index.engine.Engine.Operation.Origin.REPLICA;
import static org.elasticsearch.test.ElasticsearchTestCase.*;
import static org.elasticsearch.test.ElasticsearchTestCase.randomFrom;
import static org.hamcrest.Matchers.*;

public class InternalEngineTests extends ElasticsearchLuceneTestCase {

    protected final ShardId shardId = new ShardId(new Index("index"), 1);
    protected final Analyzer analyzer = Lucene.STANDARD_ANALYZER;

    protected ThreadPool threadPool;

    private Store store;
    private Store storeReplica;

    protected Translog translog;
    protected Translog replicaTranslog;

    protected Engine engine;
    protected Engine replicaEngine;

    private IndexSettingsService engineSettingsService;

    private IndexSettingsService replicaSettingsService;

    private Settings defaultSettings;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        defaultSettings = ImmutableSettings.builder()
                .put(EngineConfig.INDEX_COMPOUND_ON_FLUSH, randomBoolean())
                .put(EngineConfig.INDEX_GC_DELETES_SETTING, "1h") // make sure this doesn't kick in on us
                .put(EngineConfig.INDEX_FAIL_ON_CORRUPTION_SETTING, randomBoolean())
                .build(); // TODO randomize more settings
        threadPool = new ThreadPool(getClass().getName());
        store = createStore();
        store.deleteContent();
        storeReplica = createStore();
        storeReplica.deleteContent();
        engineSettingsService = new IndexSettingsService(shardId.index(), ImmutableSettings.builder().put(defaultSettings).put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).build());
        translog = createTranslog();
        engine = createEngine(engineSettingsService, store, translog);
        if (randomBoolean()) {
            ((InternalEngine)engine).config().setEnableGcDeletes(false);
        }
        replicaSettingsService = new IndexSettingsService(shardId.index(), ImmutableSettings.builder().put(defaultSettings).put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).build());
        replicaTranslog = createTranslogReplica();
        replicaEngine = createEngine(replicaSettingsService, storeReplica, replicaTranslog);
        if (randomBoolean()) {
            ((InternalEngine)engine).config().setEnableGcDeletes(false);

        }
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        replicaEngine.close();
        storeReplica.close();

        engine.close();
        store.close();
        terminate(threadPool);
    }

    private Document testDocumentWithTextField() {
        Document document = testDocument();
        document.add(new TextField("value", "test", Field.Store.YES));
        return document;
    }

    private Document testDocument() {
        return new Document();
    }


    private ParsedDocument testParsedDocument(String uid, String id, String type, String routing, long timestamp, long ttl, Document document, BytesReference source, boolean mappingsModified) {
        Field uidField = new Field("_uid", uid, UidFieldMapper.Defaults.FIELD_TYPE);
        Field versionField = new NumericDocValuesField("_version", 0);
        document.add(uidField);
        document.add(versionField);
        return new ParsedDocument(uidField, versionField, id, type, routing, timestamp, ttl, Arrays.asList(document), source, mappingsModified);
    }

    protected Store createStore() throws IOException {
       return createStore(newDirectory());
    }

    protected Store createStore(final Directory directory) throws IOException {
        final DirectoryService directoryService = new DirectoryService(shardId, EMPTY_SETTINGS) {
            @Override
            public Directory[] build() throws IOException {
                return new Directory[]{ directory };
            }

            @Override
            public long throttleTimeInNanos() {
                return 0;
            }
        };
        return new Store(shardId, EMPTY_SETTINGS, directoryService, new LeastUsedDistributor(directoryService), new DummyShardLock(shardId));
    }

    protected Translog createTranslog() throws IOException {
        return new FsTranslog(shardId, EMPTY_SETTINGS, Paths.get("work/fs-translog/primary"));
    }

    protected Translog createTranslogReplica() throws IOException {
        return new FsTranslog(shardId, EMPTY_SETTINGS, Paths.get("work/fs-translog/replica"));
    }

    protected IndexDeletionPolicy createIndexDeletionPolicy() {
        return new KeepOnlyLastDeletionPolicy(shardId, EMPTY_SETTINGS);
    }

    protected SnapshotDeletionPolicy createSnapshotDeletionPolicy() {
        return new SnapshotDeletionPolicy(createIndexDeletionPolicy());
    }

    protected MergePolicyProvider<?> createMergePolicy() {
        return new LogByteSizeMergePolicyProvider(store, new IndexSettingsService(new Index("test"), EMPTY_SETTINGS));
    }

    protected MergeSchedulerProvider createMergeScheduler(IndexSettingsService indexSettingsService) {
        return new ConcurrentMergeSchedulerProvider(shardId, EMPTY_SETTINGS, threadPool, indexSettingsService);
    }

    protected Engine createEngine(IndexSettingsService indexSettingsService, Store store, Translog translog) {
        return createEngine(indexSettingsService, store, translog, createMergeScheduler(indexSettingsService));
    }

    protected Engine createEngine(IndexSettingsService indexSettingsService, Store store, Translog translog, MergeSchedulerProvider mergeSchedulerProvider) {
        return new InternalEngine(config(indexSettingsService, store, translog, mergeSchedulerProvider));
    }

    public EngineConfig config(IndexSettingsService indexSettingsService, Store store, Translog translog, MergeSchedulerProvider mergeSchedulerProvider) {
        IndexWriterConfig iwc = newIndexWriterConfig();
        EngineConfig config = new EngineConfig(shardId, false/*per default optimization for auto generated ids is disabled*/, threadPool, new ShardIndexingService(shardId, EMPTY_SETTINGS, new ShardSlowLogIndexingService(shardId, EMPTY_SETTINGS, indexSettingsService)), indexSettingsService
                , null, store, createSnapshotDeletionPolicy(), translog, createMergePolicy(), mergeSchedulerProvider,
                iwc.getAnalyzer(), iwc.getSimilarity() , new CodecService(shardId.index()), new Engine.FailedEngineListener() {
            @Override
            public void onFailedEngine(ShardId shardId, String reason, @Nullable Throwable t) {
                // we don't need to notify anybody in this test
            }
        });


        return config;
    }

    protected static final BytesReference B_1 = new BytesArray(new byte[]{1});
    protected static final BytesReference B_2 = new BytesArray(new byte[]{2});
    protected static final BytesReference B_3 = new BytesArray(new byte[]{3});

    @Test
    public void testSegments() throws Exception {
        List<Segment> segments = engine.segments(false);
        assertThat(segments.isEmpty(), equalTo(true));
        assertThat(engine.segmentsStats().getCount(), equalTo(0l));
        assertThat(engine.segmentsStats().getMemoryInBytes(), equalTo(0l));
        final boolean defaultCompound = defaultSettings.getAsBoolean(EngineConfig.INDEX_COMPOUND_ON_FLUSH, true);

        // create a doc and refresh
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));

        ParsedDocument doc2 = testParsedDocument("2", "2", "test", null, -1, -1, testDocumentWithTextField(), B_2, false);
        engine.create(new Engine.Create(null, analyzer, newUid("2"), doc2));
        engine.refresh("test");

        segments = engine.segments(false);
        assertThat(segments.size(), equalTo(1));
        SegmentsStats stats = engine.segmentsStats();
        assertThat(stats.getCount(), equalTo(1l));
        assertThat(stats.getTermsMemoryInBytes(), greaterThan(0l));
        assertThat(stats.getStoredFieldsMemoryInBytes(), greaterThan(0l));
        assertThat(stats.getTermVectorsMemoryInBytes(), equalTo(0l));
        assertThat(stats.getNormsMemoryInBytes(), greaterThan(0l));
        assertThat(stats.getDocValuesMemoryInBytes(), greaterThan(0l));
        assertThat(segments.get(0).isCommitted(), equalTo(false));
        assertThat(segments.get(0).isSearch(), equalTo(true));
        assertThat(segments.get(0).getNumDocs(), equalTo(2));
        assertThat(segments.get(0).getDeletedDocs(), equalTo(0));
        assertThat(segments.get(0).isCompound(), equalTo(defaultCompound));
        assertThat(segments.get(0).ramTree, nullValue());

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        segments = engine.segments(false);
        assertThat(segments.size(), equalTo(1));
        assertThat(engine.segmentsStats().getCount(), equalTo(1l));
        assertThat(segments.get(0).isCommitted(), equalTo(true));
        assertThat(segments.get(0).isSearch(), equalTo(true));
        assertThat(segments.get(0).getNumDocs(), equalTo(2));
        assertThat(segments.get(0).getDeletedDocs(), equalTo(0));
        assertThat(segments.get(0).isCompound(), equalTo(defaultCompound));

        engineSettingsService.refreshSettings(ImmutableSettings.builder().put(EngineConfig.INDEX_COMPOUND_ON_FLUSH, false).build());

        ParsedDocument doc3 = testParsedDocument("3", "3", "test", null, -1, -1, testDocumentWithTextField(), B_3, false);
        engine.create(new Engine.Create(null, analyzer, newUid("3"), doc3));
        engine.refresh("test");

        segments = engine.segments(false);
        assertThat(segments.size(), equalTo(2));
        assertThat(engine.segmentsStats().getCount(), equalTo(2l));
        assertThat(engine.segmentsStats().getTermsMemoryInBytes(), greaterThan(stats.getTermsMemoryInBytes()));
        assertThat(engine.segmentsStats().getStoredFieldsMemoryInBytes(), greaterThan(stats.getStoredFieldsMemoryInBytes()));
        assertThat(engine.segmentsStats().getTermVectorsMemoryInBytes(), equalTo(0l));
        assertThat(engine.segmentsStats().getNormsMemoryInBytes(), greaterThan(stats.getNormsMemoryInBytes()));
        assertThat(engine.segmentsStats().getDocValuesMemoryInBytes(), greaterThan(stats.getDocValuesMemoryInBytes()));
        assertThat(segments.get(0).getGeneration() < segments.get(1).getGeneration(), equalTo(true));
        assertThat(segments.get(0).isCommitted(), equalTo(true));
        assertThat(segments.get(0).isSearch(), equalTo(true));
        assertThat(segments.get(0).getNumDocs(), equalTo(2));
        assertThat(segments.get(0).getDeletedDocs(), equalTo(0));
        assertThat(segments.get(0).isCompound(), equalTo(defaultCompound));


        assertThat(segments.get(1).isCommitted(), equalTo(false));
        assertThat(segments.get(1).isSearch(), equalTo(true));
        assertThat(segments.get(1).getNumDocs(), equalTo(1));
        assertThat(segments.get(1).getDeletedDocs(), equalTo(0));
        assertThat(segments.get(1).isCompound(), equalTo(false));


        engine.delete(new Engine.Delete("test", "1", newUid("1")));
        engine.refresh("test");

        segments = engine.segments(false);
        assertThat(segments.size(), equalTo(2));
        assertThat(engine.segmentsStats().getCount(), equalTo(2l));
        assertThat(segments.get(0).getGeneration() < segments.get(1).getGeneration(), equalTo(true));
        assertThat(segments.get(0).isCommitted(), equalTo(true));
        assertThat(segments.get(0).isSearch(), equalTo(true));
        assertThat(segments.get(0).getNumDocs(), equalTo(1));
        assertThat(segments.get(0).getDeletedDocs(), equalTo(1));
        assertThat(segments.get(0).isCompound(), equalTo(defaultCompound));

        assertThat(segments.get(1).isCommitted(), equalTo(false));
        assertThat(segments.get(1).isSearch(), equalTo(true));
        assertThat(segments.get(1).getNumDocs(), equalTo(1));
        assertThat(segments.get(1).getDeletedDocs(), equalTo(0));
        assertThat(segments.get(1).isCompound(), equalTo(false));

        engineSettingsService.refreshSettings(ImmutableSettings.builder().put(EngineConfig.INDEX_COMPOUND_ON_FLUSH, true).build());
        ParsedDocument doc4 = testParsedDocument("4", "4", "test", null, -1, -1, testDocumentWithTextField(), B_3, false);
        engine.create(new Engine.Create(null, analyzer, newUid("4"), doc4));
        engine.refresh("test");

        segments = engine.segments(false);
        assertThat(segments.size(), equalTo(3));
        assertThat(engine.segmentsStats().getCount(), equalTo(3l));
        assertThat(segments.get(0).getGeneration() < segments.get(1).getGeneration(), equalTo(true));
        assertThat(segments.get(0).isCommitted(), equalTo(true));
        assertThat(segments.get(0).isSearch(), equalTo(true));
        assertThat(segments.get(0).getNumDocs(), equalTo(1));
        assertThat(segments.get(0).getDeletedDocs(), equalTo(1));
        assertThat(segments.get(0).isCompound(), equalTo(defaultCompound));

        assertThat(segments.get(1).isCommitted(), equalTo(false));
        assertThat(segments.get(1).isSearch(), equalTo(true));
        assertThat(segments.get(1).getNumDocs(), equalTo(1));
        assertThat(segments.get(1).getDeletedDocs(), equalTo(0));
        assertThat(segments.get(1).isCompound(), equalTo(false));

        assertThat(segments.get(2).isCommitted(), equalTo(false));
        assertThat(segments.get(2).isSearch(), equalTo(true));
        assertThat(segments.get(2).getNumDocs(), equalTo(1));
        assertThat(segments.get(2).getDeletedDocs(), equalTo(0));
        assertThat(segments.get(2).isCompound(), equalTo(true));
    }
    
    public void testVerboseSegments() throws Exception {
        List<Segment> segments = engine.segments(true);
        assertThat(segments.isEmpty(), equalTo(true));
        
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));
        engine.refresh("test");

        segments = engine.segments(true);
        assertThat(segments.size(), equalTo(1));
        assertThat(segments.get(0).ramTree, notNullValue());
        
        ParsedDocument doc2 = testParsedDocument("2", "2", "test", null, -1, -1, testDocumentWithTextField(), B_2, false);
        engine.create(new Engine.Create(null, analyzer, newUid("2"), doc2));
        engine.refresh("test");
        ParsedDocument doc3 = testParsedDocument("3", "3", "test", null, -1, -1, testDocumentWithTextField(), B_3, false);
        engine.create(new Engine.Create(null, analyzer, newUid("3"), doc3));
        engine.refresh("test");

        segments = engine.segments(true);
        assertThat(segments.size(), equalTo(3));
        assertThat(segments.get(0).ramTree, notNullValue());
        assertThat(segments.get(1).ramTree, notNullValue());
        assertThat(segments.get(2).ramTree, notNullValue());
        
    }


    @Test
    public void testSegmentsWithMergeFlag() throws Exception {
        final Store store = createStore();
        ConcurrentMergeSchedulerProvider mergeSchedulerProvider = new ConcurrentMergeSchedulerProvider(shardId, EMPTY_SETTINGS, threadPool, new IndexSettingsService(shardId.index(), EMPTY_SETTINGS));
        final AtomicReference<CountDownLatch> waitTillMerge = new AtomicReference<>();
        final AtomicReference<CountDownLatch> waitForMerge = new AtomicReference<>();
        mergeSchedulerProvider.addListener(new MergeSchedulerProvider.Listener() {
            @Override
            public void beforeMerge(OnGoingMerge merge) {
                try {
                    if (waitTillMerge.get() != null) {
                        waitTillMerge.get().countDown();
                    }
                    if (waitForMerge.get() != null) {
                        waitForMerge.get().await();
                    }
                } catch (InterruptedException e) {
                    throw ExceptionsHelper.convertToRuntime(e);
                }
            }

            @Override
            public void afterMerge(OnGoingMerge merge) {
            }
        });

        final Engine engine = createEngine(engineSettingsService, store, createTranslog(), mergeSchedulerProvider);
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        assertThat(engine.segments(false).size(), equalTo(1));
        index = new Engine.Index(null, analyzer, newUid("2"), doc);
        engine.index(index);
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        List<Segment> segments = engine.segments(false);
        assertThat(segments.size(), equalTo(2));
        for (Segment segment : segments) {
            assertThat(segment.getMergeId(), nullValue());
        }
        index = new Engine.Index(null, analyzer, newUid("3"), doc);
        engine.index(index);
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        segments = engine.segments(false);
        assertThat(segments.size(), equalTo(3));
        for (Segment segment : segments) {
            assertThat(segment.getMergeId(), nullValue());
        }

        waitTillMerge.set(new CountDownLatch(1));
        waitForMerge.set(new CountDownLatch(1));
        engine.forceMerge(false, false);
        waitTillMerge.get().await();

        for (Segment segment : engine.segments(false)) {
            assertThat(segment.getMergeId(), notNullValue());
        }

        waitForMerge.get().countDown();

        index = new Engine.Index(null, analyzer, newUid("4"), doc);
        engine.index(index);
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        final long gen1 = store.readLastCommittedSegmentsInfo().getGeneration();
        // now, optimize and wait for merges, see that we have no merge flag
        engine.forceMerge(true, true);

        for (Segment segment : engine.segments(false)) {
            assertThat(segment.getMergeId(), nullValue());
        }
        // we could have multiple underlying merges, so the generation may increase more than once
        assertTrue(store.readLastCommittedSegmentsInfo().getGeneration() > gen1);

        final boolean flush = randomBoolean();
        final long gen2 = store.readLastCommittedSegmentsInfo().getGeneration();
        engine.forceMerge(flush, false);
        waitTillMerge.get().await();
        for (Segment segment : engine.segments(false)) {
            assertThat(segment.getMergeId(), nullValue());
        }
        waitForMerge.get().countDown();

        if (flush) {
            awaitBusy(new Predicate<Object>() {
                @Override
                public boolean apply(Object o) {
                    try {
                        // we should have had just 1 merge, so last generation should be exact
                        return store.readLastCommittedSegmentsInfo().getLastGeneration() == gen2;
                    } catch (IOException e) {
                        throw ExceptionsHelper.convertToRuntime(e);
                    }
                }
            });
        }

        engine.close();
        store.close();
    }

    @Test
    public void testSimpleOperations() throws Exception {
        Engine.Searcher searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(0));
        searchResult.close();

        // create a document
        Document document = testDocumentWithTextField();
        document.add(new Field(SourceFieldMapper.NAME, B_1.toBytes(), SourceFieldMapper.Defaults.FIELD_TYPE));
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, document, B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));

        // its not there...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 0));
        searchResult.close();

        // but, we can still get it (in realtime)
        Engine.GetResult getResult = engine.get(new Engine.Get(true, newUid("1")));
        assertThat(getResult.exists(), equalTo(true));
        assertThat(getResult.source().source.toBytesArray(), equalTo(B_1.toBytesArray()));
        assertThat(getResult.docIdAndVersion(), nullValue());
        getResult.release();

        // but, not there non realtime
        getResult = engine.get(new Engine.Get(false, newUid("1")));
        assertThat(getResult.exists(), equalTo(false));
        getResult.release();
        // refresh and it should be there
        engine.refresh("test");

        // now its there...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 1));
        searchResult.close();

        // also in non realtime
        getResult = engine.get(new Engine.Get(false, newUid("1")));
        assertThat(getResult.exists(), equalTo(true));
        assertThat(getResult.docIdAndVersion(), notNullValue());
        getResult.release();

        // now do an update
        document = testDocument();
        document.add(new TextField("value", "test1", Field.Store.YES));
        document.add(new Field(SourceFieldMapper.NAME, B_2.toBytes(), SourceFieldMapper.Defaults.FIELD_TYPE));
        doc = testParsedDocument("1", "1", "test", null, -1, -1, document, B_2, false);
        engine.index(new Engine.Index(null, analyzer, newUid("1"), doc));

        // its not updated yet...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 0));
        searchResult.close();

        // but, we can still get it (in realtime)
        getResult = engine.get(new Engine.Get(true, newUid("1")));
        assertThat(getResult.exists(), equalTo(true));
        assertThat(getResult.source().source.toBytesArray(), equalTo(B_2.toBytesArray()));
        assertThat(getResult.docIdAndVersion(), nullValue());
        getResult.release();

        // refresh and it should be updated
        engine.refresh("test");

        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 1));
        searchResult.close();

        // now delete
        engine.delete(new Engine.Delete("test", "1", newUid("1")));

        // its not deleted yet
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 1));
        searchResult.close();

        // but, get should not see it (in realtime)
        getResult = engine.get(new Engine.Get(true, newUid("1")));
        assertThat(getResult.exists(), equalTo(false));
        getResult.release();

        // refresh and it should be deleted
        engine.refresh("test");

        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 0));
        searchResult.close();

        // add it back
        document = testDocumentWithTextField();
        document.add(new Field(SourceFieldMapper.NAME, B_1.toBytes(), SourceFieldMapper.Defaults.FIELD_TYPE));
        doc = testParsedDocument("1", "1", "test", null, -1, -1, document, B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));

        // its not there...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 0));
        searchResult.close();

        // refresh and it should be there
        engine.refresh("test");

        // now its there...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 0));
        searchResult.close();

        // now flush
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        // and, verify get (in real time)
        getResult = engine.get(new Engine.Get(true, newUid("1")));
        assertThat(getResult.exists(), equalTo(true));
        assertThat(getResult.source(), nullValue());
        assertThat(getResult.docIdAndVersion(), notNullValue());
        getResult.release();

        // make sure we can still work with the engine
        // now do an update
        document = testDocument();
        document.add(new TextField("value", "test1", Field.Store.YES));
        doc = testParsedDocument("1", "1", "test", null, -1, -1, document, B_1, false);
        engine.index(new Engine.Index(null, analyzer, newUid("1"), doc));

        // its not updated yet...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 0));
        searchResult.close();

        // refresh and it should be updated
        engine.refresh("test");

        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test1")), 1));
        searchResult.close();

        engine.close();
    }

    @Test
    public void testSearchResultRelease() throws Exception {
        Engine.Searcher searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(0));
        searchResult.close();

        // create a document
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));

        // its not there...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(0));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 0));
        searchResult.close();

        // refresh and it should be there
        engine.refresh("test");

        // now its there...
        searchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 1));
        // don't release the search result yet...

        // delete, refresh and do a new search, it should not be there
        engine.delete(new Engine.Delete("test", "1", newUid("1")));
        engine.refresh("test");
        Engine.Searcher updateSearchResult = engine.acquireSearcher("test");
        MatcherAssert.assertThat(updateSearchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(0));
        updateSearchResult.close();

        // the non release search result should not see the deleted yet...
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
        MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 1));
        searchResult.close();
    }

    @Test
    public void testFailEngineOnCorruption() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        final boolean failEngine = defaultSettings.getAsBoolean(EngineConfig.INDEX_FAIL_ON_CORRUPTION_SETTING, false);
        final int failInPhase = randomIntBetween(1, 3);
        try {
            engine.recover(new Engine.RecoveryHandler() {
                @Override
                public void phase1(SnapshotIndexCommit snapshot) throws EngineException {
                    if (failInPhase == 1) {
                        throw new RuntimeException("bar", new CorruptIndexException("Foo", "fake file description"));
                    }
                }

                @Override
                public void phase2(Translog.Snapshot snapshot) throws EngineException {
                    if (failInPhase == 2) {
                        throw new RuntimeException("bar", new CorruptIndexException("Foo", "fake file description"));
                    }
                }

                @Override
                public void phase3(Translog.Snapshot snapshot) throws EngineException {
                    if (failInPhase == 3) {
                        throw new RuntimeException("bar", new CorruptIndexException("Foo", "fake file description"));
                    }
                }
            });
            fail("exception expected");
        } catch (RuntimeException ex) {

        }
        try {
            Engine.Searcher searchResult = engine.acquireSearcher("test");
            MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(1));
            MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 1));
            searchResult.close();

            ParsedDocument doc2 = testParsedDocument("2", "2", "test", null, -1, -1, testDocumentWithTextField(), B_2, false);
            engine.create(new Engine.Create(null, analyzer, newUid("2"), doc2));
            engine.refresh("foo");

            searchResult = engine.acquireSearcher("test");
            MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(new TermQuery(new Term("value", "test")), 2));
            MatcherAssert.assertThat(searchResult, EngineSearcherTotalHitsMatcher.engineSearcherTotalHits(2));
            searchResult.close();
            assertThat(failEngine, is(false));
        } catch (EngineClosedException ex) {
            assertThat(failEngine, is(true));
        }
    }


    @Test
    public void testSimpleRecover() throws Exception {
        final ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        engine.recover(new Engine.RecoveryHandler() {
            @Override
            public void phase1(SnapshotIndexCommit snapshot) throws EngineException {
                try {
                    engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
                    assertThat("flush is not allowed in phase 1", false, equalTo(true));
                } catch (FlushNotAllowedEngineException e) {
                    // all is well
                }
            }

            @Override
            public void phase2(Translog.Snapshot snapshot) throws EngineException {
                MatcherAssert.assertThat(snapshot, TranslogSizeMatcher.translogSize(0));
                try {
                    engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
                    assertThat("flush is not allowed in phase 2", false, equalTo(true));
                } catch (FlushNotAllowedEngineException e) {
                    // all is well
                }

                // but we can index
                engine.index(new Engine.Index(null, analyzer, newUid("1"), doc));
            }

            @Override
            public void phase3(Translog.Snapshot snapshot) throws EngineException {
                MatcherAssert.assertThat(snapshot, TranslogSizeMatcher.translogSize(1));
                try {
                    // we can do this here since we are on the same thread
                    engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
                    assertThat("flush is not allowed in phase 3", false, equalTo(true));
                } catch (FlushNotAllowedEngineException e) {
                    // all is well
                }
            }
        });
        // post recovery should flush the translog
        MatcherAssert.assertThat(translog.snapshot(), TranslogSizeMatcher.translogSize(0));

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        engine.close();
    }

    @Test
    public void testRecoverWithOperationsBetweenPhase1AndPhase2() throws Exception {
        ParsedDocument doc1 = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc1));
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        ParsedDocument doc2 = testParsedDocument("2", "2", "test", null, -1, -1, testDocumentWithTextField(), B_2, false);
        engine.create(new Engine.Create(null, analyzer, newUid("2"), doc2));

        engine.recover(new Engine.RecoveryHandler() {
            @Override
            public void phase1(SnapshotIndexCommit snapshot) throws EngineException {
            }

            @Override
            public void phase2(Translog.Snapshot snapshot) throws EngineException {
                Translog.Create create = (Translog.Create) snapshot.next();
                assertThat("translog snapshot should not read null", create != null, equalTo(true));
                assertThat(create.source().toBytesArray(), equalTo(B_2));
                assertThat(snapshot.next(), equalTo(null));
            }

            @Override
            public void phase3(Translog.Snapshot snapshot) throws EngineException {
                MatcherAssert.assertThat(snapshot, TranslogSizeMatcher.translogSize(0));
            }
        });

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        engine.close();
    }

    @Test
    public void testRecoverWithOperationsBetweenPhase1AndPhase2AndPhase3() throws Exception {
        ParsedDocument doc1 = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
        engine.create(new Engine.Create(null, analyzer, newUid("1"), doc1));
        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        ParsedDocument doc2 = testParsedDocument("2", "2", "test", null, -1, -1, testDocumentWithTextField(), B_2, false);
        engine.create(new Engine.Create(null, analyzer, newUid("2"), doc2));

        engine.recover(new Engine.RecoveryHandler() {
            @Override
            public void phase1(SnapshotIndexCommit snapshot) throws EngineException {
            }

            @Override
            public void phase2(Translog.Snapshot snapshot) throws EngineException {
                Translog.Create create = (Translog.Create) snapshot.next();
                assertThat(create != null, equalTo(true));
                assertThat(snapshot.next(), equalTo(null));
                assertThat(create.source().toBytesArray(), equalTo(B_2));

                // add for phase3
                ParsedDocument doc3 = testParsedDocument("3", "3", "test", null, -1, -1, testDocumentWithTextField(), B_3, false);
                engine.create(new Engine.Create(null, analyzer, newUid("3"), doc3));
            }

            @Override
            public void phase3(Translog.Snapshot snapshot) throws EngineException {
                Translog.Create create = (Translog.Create) snapshot.next();
                assertThat(create != null, equalTo(true));
                assertThat(snapshot.next(), equalTo(null));
                assertThat(create.source().toBytesArray(), equalTo(B_3));
            }
        });

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
        engine.close();
    }

    @Test
    public void testVersioningNewCreate() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Create create = new Engine.Create(null, analyzer, newUid("1"), doc);
        engine.create(create);
        assertThat(create.version(), equalTo(1l));

        create = new Engine.Create(null, analyzer, newUid("1"), doc, create.version(), create.versionType().versionTypeForReplicationAndRecovery(), REPLICA, 0);
        replicaEngine.create(create);
        assertThat(create.version(), equalTo(1l));
    }

    @Test
    public void testExternalVersioningNewCreate() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Create create = new Engine.Create(null, analyzer, newUid("1"), doc, 12, VersionType.EXTERNAL, Engine.Operation.Origin.PRIMARY, 0);
        engine.create(create);
        assertThat(create.version(), equalTo(12l));

        create = new Engine.Create(null, analyzer, newUid("1"), doc, create.version(), create.versionType().versionTypeForReplicationAndRecovery(), REPLICA, 0);
        replicaEngine.create(create);
        assertThat(create.version(), equalTo(12l));
    }

    @Test
    public void testVersioningNewIndex() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(1l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc, index.version(), index.versionType().versionTypeForReplicationAndRecovery(), REPLICA, 0);
        replicaEngine.index(index);
        assertThat(index.version(), equalTo(1l));
    }

    @Test
    public void testExternalVersioningNewIndex() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc, 12, VersionType.EXTERNAL, PRIMARY, 0);
        engine.index(index);
        assertThat(index.version(), equalTo(12l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc, index.version(), index.versionType().versionTypeForReplicationAndRecovery(), REPLICA, 0);
        replicaEngine.index(index);
        assertThat(index.version(), equalTo(12l));
    }

    @Test
    public void testVersioningIndexConflict() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(1l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(2l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc, 1l, VersionType.INTERNAL, Engine.Operation.Origin.PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // future versions should not work as well
        index = new Engine.Index(null, analyzer, newUid("1"), doc, 3l, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }

    @Test
    public void testExternalVersioningIndexConflict() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc, 12, VersionType.EXTERNAL, PRIMARY, 0);
        engine.index(index);
        assertThat(index.version(), equalTo(12l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc, 14, VersionType.EXTERNAL, PRIMARY, 0);
        engine.index(index);
        assertThat(index.version(), equalTo(14l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc, 13, VersionType.EXTERNAL, PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }

    @Test
    public void testVersioningIndexConflictWithFlush() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(1l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(2l));

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        index = new Engine.Index(null, analyzer, newUid("1"), doc, 1l, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // future versions should not work as well
        index = new Engine.Index(null, analyzer, newUid("1"), doc, 3l, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }

    @Test
    public void testExternalVersioningIndexConflictWithFlush() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc, 12, VersionType.EXTERNAL, PRIMARY, 0);
        engine.index(index);
        assertThat(index.version(), equalTo(12l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc, 14, VersionType.EXTERNAL, PRIMARY, 0);
        engine.index(index);
        assertThat(index.version(), equalTo(14l));

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        index = new Engine.Index(null, analyzer, newUid("1"), doc, 13, VersionType.EXTERNAL, PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }

    @Test
    public void testVersioningDeleteConflict() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(1l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(2l));

        Engine.Delete delete = new Engine.Delete("test", "1", newUid("1"), 1l, VersionType.INTERNAL, PRIMARY, 0, false);
        try {
            engine.delete(delete);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // future versions should not work as well
        delete = new Engine.Delete("test", "1", newUid("1"), 3l, VersionType.INTERNAL, PRIMARY, 0, false);
        try {
            engine.delete(delete);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // now actually delete
        delete = new Engine.Delete("test", "1", newUid("1"), 2l, VersionType.INTERNAL, PRIMARY, 0, false);
        engine.delete(delete);
        assertThat(delete.version(), equalTo(3l));

        // now check if we can index to a delete doc with version
        index = new Engine.Index(null, analyzer, newUid("1"), doc, 2l, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // we shouldn't be able to create as well
        Engine.Create create = new Engine.Create(null, analyzer, newUid("1"), doc, 2l, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.create(create);
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }

    @Test
    public void testVersioningDeleteConflictWithFlush() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(1l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(2l));

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        Engine.Delete delete = new Engine.Delete("test", "1", newUid("1"), 1l, VersionType.INTERNAL, PRIMARY, 0, false);
        try {
            engine.delete(delete);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // future versions should not work as well
        delete = new Engine.Delete("test", "1", newUid("1"), 3l, VersionType.INTERNAL, PRIMARY, 0, false);
        try {
            engine.delete(delete);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        // now actually delete
        delete = new Engine.Delete("test", "1", newUid("1"), 2l, VersionType.INTERNAL, PRIMARY, 0, false);
        engine.delete(delete);
        assertThat(delete.version(), equalTo(3l));

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        // now check if we can index to a delete doc with version
        index = new Engine.Index(null, analyzer, newUid("1"), doc, 2l, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // we shouldn't be able to create as well
        Engine.Create create = new Engine.Create(null, analyzer, newUid("1"), doc, 2l, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.create(create);
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }

    @Test
    public void testVersioningCreateExistsException() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Create create = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, 0);
        engine.create(create);
        assertThat(create.version(), equalTo(1l));

        create = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.create(create);
            fail();
        } catch (DocumentAlreadyExistsException e) {
            // all is well
        }
    }

    @Test
    public void testVersioningCreateExistsExceptionWithFlush() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Create create = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, 0);
        engine.create(create);
        assertThat(create.version(), equalTo(1l));

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        create = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, 0);
        try {
            engine.create(create);
            fail();
        } catch (DocumentAlreadyExistsException e) {
            // all is well
        }
    }

    @Test
    public void testVersioningReplicaConflict1() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(1l));

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(2l));

        // apply the second index to the replica, should work fine
        index = new Engine.Index(null, analyzer, newUid("1"), doc, index.version(), VersionType.INTERNAL.versionTypeForReplicationAndRecovery(), REPLICA, 0);
        replicaEngine.index(index);
        assertThat(index.version(), equalTo(2l));

        // now, the old one should not work
        index = new Engine.Index(null, analyzer, newUid("1"), doc, 1l, VersionType.INTERNAL.versionTypeForReplicationAndRecovery(), REPLICA, 0);
        try {
            replicaEngine.index(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // second version on replica should fail as well
        try {
            index = new Engine.Index(null, analyzer, newUid("1"), doc, 2l
                    , VersionType.INTERNAL.versionTypeForReplicationAndRecovery(), REPLICA, 0);
            replicaEngine.index(index);
            assertThat(index.version(), equalTo(2l));
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }

    @Test
    public void testVersioningReplicaConflict2() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(1l));

        // apply the first index to the replica, should work fine
        index = new Engine.Index(null, analyzer, newUid("1"), doc, 1l
                , VersionType.INTERNAL.versionTypeForReplicationAndRecovery(), REPLICA, 0);
        replicaEngine.index(index);
        assertThat(index.version(), equalTo(1l));

        // index it again
        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertThat(index.version(), equalTo(2l));

        // now delete it
        Engine.Delete delete = new Engine.Delete("test", "1", newUid("1"));
        engine.delete(delete);
        assertThat(delete.version(), equalTo(3l));

        // apply the delete on the replica (skipping the second index)
        delete = new Engine.Delete("test", "1", newUid("1"), 3l
                , VersionType.INTERNAL.versionTypeForReplicationAndRecovery(), REPLICA, 0, false);
        replicaEngine.delete(delete);
        assertThat(delete.version(), equalTo(3l));

        // second time delete with same version should fail
        try {
            delete = new Engine.Delete("test", "1", newUid("1"), 3l
                    , VersionType.INTERNAL.versionTypeForReplicationAndRecovery(), REPLICA, 0, false);
            replicaEngine.delete(delete);
            fail("excepted VersionConflictEngineException to be thrown");
        } catch (VersionConflictEngineException e) {
            // all is well
        }

        // now do the second index on the replica, it should fail
        try {
            index = new Engine.Index(null, analyzer, newUid("1"), doc, 2l, VersionType.INTERNAL.versionTypeForReplicationAndRecovery(), REPLICA, 0);
            replicaEngine.index(index);
            fail("excepted VersionConflictEngineException to be thrown");
        } catch (VersionConflictEngineException e) {
            // all is well
        }
    }


    @Test
    public void testBasicCreatedFlag() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertTrue(index.created());

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertFalse(index.created());

        engine.delete(new Engine.Delete(null, "1", newUid("1")));

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertTrue(index.created());
    }

    @Test
    public void testCreatedFlagAfterFlush() {
        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        Engine.Index index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertTrue(index.created());

        engine.delete(new Engine.Delete(null, "1", newUid("1")));

        engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);

        index = new Engine.Index(null, analyzer, newUid("1"), doc);
        engine.index(index);
        assertTrue(index.created());
    }

    private static class MockAppender extends AppenderSkeleton {
        public boolean sawIndexWriterMessage;

        public boolean sawIndexWriterIFDMessage;

        @Override
        protected void append(LoggingEvent event) {
            if (event.getLevel() == Level.TRACE && event.getMessage().toString().contains("[index][1] ")) {
                if (event.getLoggerName().endsWith("lucene.iw") &&
                        event.getMessage().toString().contains("IW: apply all deletes during flush")) {
                    sawIndexWriterMessage = true;
                }
                if (event.getLoggerName().endsWith("lucene.iw.ifd")) {
                    sawIndexWriterIFDMessage = true;
                }
            }
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    // #5891: make sure IndexWriter's infoStream output is
    // sent to lucene.iw with log level TRACE:

    @Test
    public void testIndexWriterInfoStream() {
        MockAppender mockAppender = new MockAppender();

        Logger rootLogger = Logger.getRootLogger();
        Level savedLevel = rootLogger.getLevel();
        rootLogger.addAppender(mockAppender);
        rootLogger.setLevel(Level.DEBUG);

        try {
            // First, with DEBUG, which should NOT log IndexWriter output:
            ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
            engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));
            engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
            assertFalse(mockAppender.sawIndexWriterMessage);

            // Again, with TRACE, which should log IndexWriter output:
            rootLogger.setLevel(Level.TRACE);
            engine.create(new Engine.Create(null, analyzer, newUid("2"), doc));
            engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
            assertTrue(mockAppender.sawIndexWriterMessage);

        } finally {
            rootLogger.removeAppender(mockAppender);
            rootLogger.setLevel(savedLevel);
        }
    }

    // #8603: make sure we can separately log IFD's messages
    public void testIndexWriterIFDInfoStream() {
        MockAppender mockAppender = new MockAppender();

        // Works when running this test inside Intellij:
        Logger iwIFDLogger = LogManager.exists("org.elasticsearch.index.engine.internal.lucene.iw.ifd");
        if (iwIFDLogger == null) {
            // Works when running this test from command line:
            iwIFDLogger = LogManager.exists("index.engine.internal.lucene.iw.ifd");
            assertNotNull(iwIFDLogger);
        }

        Level savedLevel = iwIFDLogger.getLevel();
        iwIFDLogger.addAppender(mockAppender);
        iwIFDLogger.setLevel(Level.DEBUG);

        try {
            // First, with DEBUG, which should NOT log IndexWriter output:
            ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocumentWithTextField(), B_1, false);
            engine.create(new Engine.Create(null, analyzer, newUid("1"), doc));
            engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
            assertFalse(mockAppender.sawIndexWriterMessage);
            assertFalse(mockAppender.sawIndexWriterIFDMessage);

            // Again, with TRACE, which should only log IndexWriter IFD output:
            iwIFDLogger.setLevel(Level.TRACE);
            engine.create(new Engine.Create(null, analyzer, newUid("2"), doc));
            engine.flush(Engine.FlushType.COMMIT_TRANSLOG, false, false);
            assertFalse(mockAppender.sawIndexWriterMessage);
            assertTrue(mockAppender.sawIndexWriterIFDMessage);

        } finally {
            iwIFDLogger.removeAppender(mockAppender);
            iwIFDLogger.setLevel(null);
        }
    }

    @Slow
    @Test
    public void testEnableGcDeletes() throws Exception {

        Store store = createStore();

        // Make sure enableGCDeletes == false works:
        Settings settings = ImmutableSettings.builder()
                .put(EngineConfig.INDEX_GC_DELETES_SETTING, "0ms")
                .build();

        Engine engine = new InternalEngine(config(engineSettingsService, store, createTranslog(), createMergeScheduler(engineSettingsService)));
        ((InternalEngine)engine).config().setEnableGcDeletes(false);

        // Add document
        Document document = testDocument();
        document.add(new TextField("value", "test1", Field.Store.YES));

        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, document, B_2, false);
        engine.index(new Engine.Index(null, analyzer, newUid("1"), doc, 1, VersionType.EXTERNAL, Engine.Operation.Origin.PRIMARY, System.nanoTime(), false));

        // Delete document we just added:
        engine.delete(new Engine.Delete("test", "1", newUid("1"), 10, VersionType.EXTERNAL, Engine.Operation.Origin.PRIMARY, System.nanoTime(), false));

        // Get should not find the document
        Engine.GetResult getResult = engine.get(new Engine.Get(true, newUid("1")));
        assertThat(getResult.exists(), equalTo(false));

        // Give the gc pruning logic a chance to kick in
        Thread.sleep(1000);

        if (randomBoolean()) {
            engine.refresh("test");
        }

        // Delete non-existent document
        engine.delete(new Engine.Delete("test", "2", newUid("2"), 10, VersionType.EXTERNAL, Engine.Operation.Origin.PRIMARY, System.nanoTime(), false));

        // Get should not find the document (we never indexed uid=2):
        getResult = engine.get(new Engine.Get(true, newUid("2")));
        assertThat(getResult.exists(), equalTo(false));

        // Try to index uid=1 with a too-old version, should fail:
        try {
            engine.index(new Engine.Index(null, analyzer, newUid("1"), doc, 2, VersionType.EXTERNAL, Engine.Operation.Origin.PRIMARY, System.nanoTime()));
            fail("did not hit expected exception");
        } catch (VersionConflictEngineException vcee) {
            // expected
        }

        // Get should still not find the document
        getResult = engine.get(new Engine.Get(true, newUid("1")));
        assertThat(getResult.exists(), equalTo(false));

        // Try to index uid=2 with a too-old version, should fail:
        try {
            engine.index(new Engine.Index(null, analyzer, newUid("2"), doc, 2, VersionType.EXTERNAL, Engine.Operation.Origin.PRIMARY, System.nanoTime()));
            fail("did not hit expected exception");
        } catch (VersionConflictEngineException vcee) {
            // expected
        }

        // Get should not find the document
        getResult = engine.get(new Engine.Get(true, newUid("2")));
        assertThat(getResult.exists(), equalTo(false));
        engine.close();
        store.close();
    }

    protected Term newUid(String id) {
        return new Term("_uid", id);
    }

    @Test
    public void testExtractShardId() {
        try (Engine.Searcher test = this.engine.acquireSearcher("test")) {
            ShardId shardId = ShardUtils.extractShardId(test.reader());
            assertNotNull(shardId);
            assertEquals(shardId, ((InternalEngine) engine).config().getShardId());
        }
    }

    /**
     * Random test that throws random exception and ensures all references are
     * counted down / released and resources are closed.
     */
    @Test
    public void testFailStart() throws IOException {
        // this test fails if any reader, searcher or directory is not closed - MDW FTW
        final int iters = scaledRandomIntBetween(10, 100);
        for (int i = 0; i < iters; i++) {
            MockDirectoryWrapper wrapper = newMockDirectory();
            wrapper.setFailOnOpenInput(randomBoolean());
            wrapper.setAllowRandomFileNotFoundException(randomBoolean());
            wrapper.setRandomIOExceptionRate(randomDouble());
            wrapper.setRandomIOExceptionRateOnOpen(randomDouble());
            try (Store store = createStore(wrapper)) {
                int refCount = store.refCount();
                assertTrue("refCount: "+ store.refCount(), store.refCount() > 0);
                Translog translog = createTranslog();
                Settings build = ImmutableSettings.builder()
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).build();
                IndexSettingsService indexSettingsService = new IndexSettingsService(shardId.index(), build);
                Engine holder;
                try {
                    holder = createEngine(indexSettingsService, store, translog);
                } catch (EngineCreationFailureException ex) {
                    assertEquals(store.refCount(), refCount);
                    continue;
                }
                indexSettingsService.refreshSettings(ImmutableSettings.builder()
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                        .put(EngineConfig.INDEX_FAIL_ON_CORRUPTION_SETTING, true).build());

                assertEquals(store.refCount(), refCount+1);
                final int numStarts = scaledRandomIntBetween(1, 5);
                for (int j = 0; j < numStarts; j++) {
                    try {
                        assertEquals(store.refCount(), refCount + 1);
                        holder.close();
                        holder = createEngine(indexSettingsService, store, translog);
                        assertEquals(store.refCount(), refCount + 1);
                    } catch (EngineCreationFailureException ex) {
                        // all is fine
                        assertEquals(store.refCount(), refCount);
                        break;
                    }
                }
                translog.close();
                holder.close();
                assertEquals(store.refCount(), refCount);
            }
        }
    }

    @Test
    public void testSettings() {
        IndexDynamicSettingsModule settings = new IndexDynamicSettingsModule();
        assertTrue(settings.containsSetting(EngineConfig.INDEX_FAIL_ON_CORRUPTION_SETTING));
        assertTrue(settings.containsSetting(EngineConfig.INDEX_COMPOUND_ON_FLUSH));
        assertTrue(settings.containsSetting(EngineConfig.INDEX_GC_DELETES_SETTING));
        assertTrue(settings.containsSetting(EngineConfig.INDEX_CODEC_SETTING));
        assertTrue(settings.containsSetting(EngineConfig.INDEX_FAIL_ON_MERGE_FAILURE_SETTING));
        assertTrue(settings.containsSetting(EngineConfig.INDEX_CONCURRENCY_SETTING));
        InternalEngine engine = (InternalEngine) this.engine;
        CodecService codecService = new CodecService(shardId.index());
        final int iters = between(1, 20);
        for (int i = 0; i < iters; i++) {
            boolean compoundOnFlush = randomBoolean();
            boolean failOnCorruption = randomBoolean();
            boolean failOnMerge = randomBoolean();
            long gcDeletes = Math.max(0, randomLong());
            int indexConcurrency = randomIntBetween(1, 20);
            String codecName = randomFrom(codecService.availableCodecs());

            Settings build = ImmutableSettings.builder()
                    .put(EngineConfig.INDEX_FAIL_ON_CORRUPTION_SETTING, failOnCorruption)
                    .put(EngineConfig.INDEX_COMPOUND_ON_FLUSH, compoundOnFlush)
                    .put(EngineConfig.INDEX_GC_DELETES_SETTING, gcDeletes)
                    .put(EngineConfig.INDEX_CODEC_SETTING, codecName)
                    .put(EngineConfig.INDEX_FAIL_ON_MERGE_FAILURE_SETTING, failOnMerge)
                    .put(EngineConfig.INDEX_CONCURRENCY_SETTING, indexConcurrency)
                    .build();

            engineSettingsService.refreshSettings(build);
            LiveIndexWriterConfig currentIndexWriterConfig = engine.getCurrentIndexWriterConfig();
            assertEquals(engine.config().isCompoundOnFlush(), compoundOnFlush);
            assertEquals(currentIndexWriterConfig.getUseCompoundFile(), compoundOnFlush);


            assertEquals(engine.config().getGcDeletesInMillis(), gcDeletes);
            assertEquals(engine.getGcDeletesInMillis(), gcDeletes);

            assertEquals(engine.config().getCodec().getName(), codecService.codec(codecName).getName());
            assertEquals(currentIndexWriterConfig.getCodec().getName(), codecService.codec(codecName).getName());


            assertEquals(engine.config().isFailEngineOnCorruption(), failOnCorruption);

            assertEquals(engine.config().isFailOnMergeFailure(), failOnMerge); // only on the holder

            assertEquals(engine.config().getIndexConcurrency(), indexConcurrency);
            assertEquals(currentIndexWriterConfig.getMaxThreadStates(), indexConcurrency);


        }
    }

    @Test
    public void testRetryWithAutogeneratedIdWorksAndNoDuplicateDocs() throws IOException {

        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        boolean canHaveDuplicates = false;
        boolean autoGeneratedId = true;

        Engine.Create index = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        engine.create(index);
        assertThat(index.version(), equalTo(1l));

        index = new Engine.Create(null, analyzer, newUid("1"), doc, index.version(), index.versionType().versionTypeForReplicationAndRecovery(), REPLICA, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        replicaEngine.create(index);
        assertThat(index.version(), equalTo(1l));

        canHaveDuplicates = true;
        index = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        engine.create(index);
        assertThat(index.version(), equalTo(1l));
        engine.refresh("test");
        Engine.Searcher searcher = engine.acquireSearcher("test");
        TopDocs topDocs = searcher.searcher().search(new MatchAllDocsQuery(), 10);
        assertThat(topDocs.totalHits, equalTo(1));

        index = new Engine.Create(null, analyzer, newUid("1"), doc, index.version(), index.versionType().versionTypeForReplicationAndRecovery(), REPLICA, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        try {
            replicaEngine.create(index);
            fail();
        } catch (VersionConflictEngineException e) {
            // we ignore version conflicts on replicas, see TransportShardReplicationOperationAction.ignoreReplicaException
        }
        replicaEngine.refresh("test");
        Engine.Searcher replicaSearcher = replicaEngine.acquireSearcher("test");
        topDocs = replicaSearcher.searcher().search(new MatchAllDocsQuery(), 10);
        assertThat(topDocs.totalHits, equalTo(1));
        searcher.close();
        replicaSearcher.close();
    }

    @Test
    public void testRetryWithAutogeneratedIdsAndWrongOrderWorksAndNoDuplicateDocs() throws IOException {

        ParsedDocument doc = testParsedDocument("1", "1", "test", null, -1, -1, testDocument(), B_1, false);
        boolean canHaveDuplicates = true;
        boolean autoGeneratedId = true;

        Engine.Create firstIndexRequest = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        engine.create(firstIndexRequest);
        assertThat(firstIndexRequest.version(), equalTo(1l));

        Engine.Create firstIndexRequestReplica = new Engine.Create(null, analyzer, newUid("1"), doc, firstIndexRequest.version(), firstIndexRequest.versionType().versionTypeForReplicationAndRecovery(), REPLICA, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        replicaEngine.create(firstIndexRequestReplica);
        assertThat(firstIndexRequestReplica.version(), equalTo(1l));

        canHaveDuplicates = false;
        Engine.Create secondIndexRequest = new Engine.Create(null, analyzer, newUid("1"), doc, Versions.MATCH_ANY, VersionType.INTERNAL, PRIMARY, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        try {
            engine.create(secondIndexRequest);
            fail();
        } catch (DocumentAlreadyExistsException e) {
            // we can ignore the exception. In case this happens because the retry request arrived first then this error will not be sent back anyway.
            // in any other case this is an actual error
        }
        engine.refresh("test");
        Engine.Searcher searcher = engine.acquireSearcher("test");
        TopDocs topDocs = searcher.searcher().search(new MatchAllDocsQuery(), 10);
        assertThat(topDocs.totalHits, equalTo(1));

        Engine.Create secondIndexRequestReplica = new Engine.Create(null, analyzer, newUid("1"), doc, firstIndexRequest.version(), firstIndexRequest.versionType().versionTypeForReplicationAndRecovery(), REPLICA, System.nanoTime(), canHaveDuplicates, autoGeneratedId);
        try {
            replicaEngine.create(secondIndexRequestReplica);
            fail();
        } catch (VersionConflictEngineException e) {
            // we ignore version conflicts on replicas, see TransportShardReplicationOperationAction.ignoreReplicaException.
        }
        replicaEngine.refresh("test");
        Engine.Searcher replicaSearcher = replicaEngine.acquireSearcher("test");
        topDocs = replicaSearcher.searcher().search(new MatchAllDocsQuery(), 10);
        assertThat(topDocs.totalHits, equalTo(1));
        searcher.close();
        replicaSearcher.close();
    }

}
