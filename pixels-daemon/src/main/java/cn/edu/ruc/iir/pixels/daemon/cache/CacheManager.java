package cn.edu.ruc.iir.pixels.daemon.cache;

import cn.edu.ruc.iir.pixels.cache.PixelsCacheConfig;
import cn.edu.ruc.iir.pixels.cache.PixelsCacheUtil;
import cn.edu.ruc.iir.pixels.cache.PixelsCacheWriter;
import cn.edu.ruc.iir.pixels.common.exception.MetadataException;
import cn.edu.ruc.iir.pixels.common.metadata.MetadataService;
import cn.edu.ruc.iir.pixels.common.metadata.domain.Layout;
import cn.edu.ruc.iir.pixels.common.utils.Constants;
import cn.edu.ruc.iir.pixels.common.utils.EtcdUtil;
import cn.edu.ruc.iir.pixels.daemon.Server;
import com.coreos.jetcd.Lease;
import com.coreos.jetcd.Watch;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.options.WatchOption;
import com.coreos.jetcd.watch.WatchEvent;
import com.coreos.jetcd.watch.WatchResponse;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * pixels cache manager.
 *
 * @author guodong
 */
public class CacheManager
        implements Server
{
    private static Logger logger = LogManager.getLogger(CacheManager.class);
    // cache status: initializing(0), ready(1), updating(2), dead(-1)
    private static AtomicInteger cacheStatus = new AtomicInteger(0);

    private PixelsCacheWriter cacheWriter = null;
    private MetadataService metadataService = null;
    private CacheManagerRegister cacheManagerRegister;
    private final PixelsCacheConfig cacheConfig;
    private final EtcdUtil etcdUtil;
    private final ScheduledExecutorService scheduledExecutor;
    private String hostName;
    private boolean initializeSuccess = false;
    private int localCacheVersion = 0;

    public CacheManager()
    {
        this.cacheConfig = new PixelsCacheConfig();
        this.etcdUtil = EtcdUtil.Instance();
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        this.hostName = System.getenv("HOSTNAME");
        logger.debug("HostName from system env: " + hostName);
        if (hostName == null) {
            try {
                this.hostName = InetAddress.getLocalHost().getHostName();
                logger.debug("HostName from InetAddress: " + hostName);
            }
            catch (UnknownHostException e) {
                logger.debug("Hostname is null. Exit");
                return;
            }
        }
        logger.debug("HostName: " + hostName);
        initialize();
    }

    /**
     * Initialize CacheManager
     *
     * 1. check if cache file exists.
     *    if exists, check if existing cache version is the same as current cache version in etcd.
     *      if not, existing cache is out of date, goto step #2.
     * 2. else, update caches with latest layout in etcd/mysql.
     * 3. update the status of CacheManager in etcd
     * 4. start a scheduled thread to update node(CacheManager) status
     * 5. add a watcher to listen to changes of the cache version in etcd.
     *    if there is a new version, we need update caches according to new layouts.
     * */
    private void initialize()
    {
        try {
            KeyValue cacheCoordinatorKV = etcdUtil.getKeyValue(Constants.CACHE_COORDINATOR_LITERAL);
            if (cacheCoordinatorKV == null) {
                logger.info("No coordinator found. Exit");
                return;
            }
            // init cache writer and metadata service
            Configuration conf = new Configuration();
            conf.set("fs.hdfs.impl", org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
            conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
            FileSystem fs = FileSystem.get(URI.create(cacheConfig.getWarehousePath()), conf);
            this.cacheWriter =
                    PixelsCacheWriter.newBuilder()
                                     .setCacheLocation(cacheConfig.getCacheLocation())
                                     .setCacheSize(cacheConfig.getCacheSize())
                                     .setIndexLocation(cacheConfig.getIndexLocation())
                                     .setIndexSize(cacheConfig.getIndexSize())
                                     .setOverwrite(false)
                                     .setFS(fs)
                                     .setHostName(hostName)
                                     .build();
            this.metadataService = new MetadataService(cacheConfig.getMetaHost(), cacheConfig.getMetaPort());
            localCacheVersion = PixelsCacheUtil.getIndexVersion(cacheWriter.getIndexFile());
            logger.debug("Local cache version: " + localCacheVersion);
            KeyValue globalCacheVersionKV = etcdUtil.getKeyValue(Constants.CACHE_VERSION_LITERAL);
            if (null != globalCacheVersionKV) {
                int globalCacheVersion = Integer.parseInt(globalCacheVersionKV.getValue().toStringUtf8());
                logger.debug("Current global cache version: " + globalCacheVersion);
                // if cache file exists already. we need check local cache version with global cache version stored in etcd
                if (localCacheVersion < globalCacheVersion) {
                    // if global version is not consistent with the local one. update local cache.
                    update(globalCacheVersion);
                }
            }
            // register datanode
            Lease leaseClient = etcdUtil.getClient().getLeaseClient();
            long leaseId = leaseClient.grant(cacheConfig.getNodeLeaseTTL()).get(10, TimeUnit.SECONDS).getID();
            etcdUtil.putKeyValueWithLeaseId(Constants.CACHE_NODE_STATUS_LITERAL + hostName,
                                            "" + cacheStatus.get(), leaseId);
            // start a scheduled thread to update node status periodically
            this.cacheManagerRegister = new CacheManagerRegister(leaseClient, leaseId);
            scheduledExecutor.scheduleAtFixedRate(cacheManagerRegister, 1, 10, TimeUnit.SECONDS);
            cacheStatus.set(1);
            etcdUtil.putKeyValue(Constants.CACHE_NODE_STATUS_LITERAL + hostName, "" + cacheStatus.get());
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
            initializeSuccess = true;
        }
        catch (Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean update(int version)
            throws MetadataException
    {
        logger.info("Update cache to version " + version);
        List<Layout> matchedLayouts = metadataService.getLayout(cacheConfig.getSchema(), cacheConfig.getTable(), version);
        if (!matchedLayouts.isEmpty()) {
            // update cache status
            cacheStatus.set(2);
            etcdUtil.putKeyValue(Constants.CACHE_NODE_STATUS_LITERAL + hostName, "" + cacheStatus.get());
            logger.debug("Update cache. Status changed to 2");
            // update cache content
            if (cacheWriter.updateAll(version, matchedLayouts.iterator().next())) {
                cacheStatus.set(1);
                etcdUtil.putKeyValue(Constants.CACHE_NODE_STATUS_LITERAL + hostName, "" + cacheStatus.get());
                localCacheVersion = version;
                logger.debug("Update cache content ok. Status changed back to 1");
                return true;
            }
            else {
                logger.warn("Cache update to version" + version + " failed.");
            }
        }
        else {
            logger.warn("No matching layout found for the update version " + version);
        }
        return false;
    }

    @Override
    public void run()
    {
        logger.info("Starting cache manager");
        if (false == initializeSuccess) {
            logger.info("Initialization failed. Stop now.");
            return;
        }
        Watch watch = etcdUtil.getClient().getWatchClient();
        Watch.Watcher watcher = watch.watch(
                ByteSequence.fromString(Constants.CACHE_VERSION_LITERAL), WatchOption.DEFAULT);
        outer_loop: while (cacheStatus.get() > 0) {
            try {
                WatchResponse watchResponse = watcher.listen();
                for (WatchEvent event : watchResponse.getEvents()) {
                    // update a new version
                    if (event.getEventType() == WatchEvent.EventType.PUT) {
                        int version = Integer.parseInt(event.getKeyValue().getValue().toStringUtf8());
                        logger.debug("Cache version update detected, new global version is " + version);
                        if (version > localCacheVersion) {
                            logger.debug("New global version is greater than the local version, update the local cache");
                            update(version);
                        }
                    }
                    else if (event.getEventType() == WatchEvent.EventType.DELETE){
                        logger.warn("Cache version deletion detected, the cluster is corrupted. Stop now.");
                        cacheStatus.set(-1);
                        break outer_loop;
                    }
                }
            }
            catch (InterruptedException | MetadataException e) {
                logger.error(e.getMessage());
                e.printStackTrace();
                break;
            }
        }
    }

    @Override
    public boolean isRunning()
    {
        return cacheStatus.get() >= 0;
    }

    @Override
    public void shutdown()
    {
        cacheStatus.set(-1);
        cacheManagerRegister.stop();
        etcdUtil.delete(Constants.CACHE_NODE_STATUS_LITERAL + hostName);
        logger.info("CacheManager on " + hostName + " shut down.");
        this.scheduledExecutor.shutdownNow();
    }

    /**
     * Scheduled register to update caching node status and keep its registration alive.
     * */
    private static class CacheManagerRegister
            implements Runnable
    {
        private final Lease leaseClient;
        private final long leaseId;

        CacheManagerRegister(Lease leaseClient, long leaseId)
        {
            this.leaseClient = leaseClient;
            this.leaseId = leaseId;

        }

        @Override
        public void run()
        {
            try {
                leaseClient.keepAliveOnce(leaseId);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void stop()
        {
            leaseClient.revoke(leaseId);
            leaseClient.close();
        }
    }
}