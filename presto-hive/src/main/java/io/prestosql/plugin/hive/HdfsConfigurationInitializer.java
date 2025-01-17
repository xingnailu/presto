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
package io.prestosql.plugin.hive;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import io.airlift.units.Duration;
import io.prestosql.hadoop.SocksSocketFactory;
import io.prestosql.plugin.hive.s3.ConfigurationInitializer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.orc.OrcConf;
import org.apache.parquet.hadoop.ParquetOutputFormat;

import javax.inject.Inject;
import javax.net.SocketFactory;

import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.plugin.hive.util.ConfigurationUtils.copy;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.fs.CommonConfigurationKeys.IPC_PING_INTERVAL_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_RPC_PROTECTION;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_RPC_SOCKET_FACTORY_CLASS_DEFAULT_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SOCKS_SERVER_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_TIMEOUT_KEY;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_CLIENT_KEY_PROVIDER_CACHE_EXPIRY_MS;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_CLIENT_SOCKET_TIMEOUT_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.DFS_DOMAIN_SOCKET_PATH_KEY;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.COMPRESSRESULT;
import static org.apache.hadoop.io.SequenceFile.CompressionType.BLOCK;

public class HdfsConfigurationInitializer
{
    private final HostAndPort socksProxy;
    private final Duration ipcPingInterval;
    private final Duration dfsTimeout;
    private final Duration dfsConnectTimeout;
    private final int dfsConnectMaxRetries;
    private final int dfsKeyProviderCacheTtlMillis;
    private final String domainSocketPath;
    private final Configuration resourcesConfiguration;
    private final HiveCompressionCodec compressionCodec;
    private final int fileSystemMaxCacheSize;
    private final Set<ConfigurationInitializer> configurationInitializers;
    private final boolean isHdfsWireEncryptionEnabled;
    private int textMaxLineLength;

    @VisibleForTesting
    public HdfsConfigurationInitializer(HiveConfig config)
    {
        this(config, ImmutableSet.of());
    }

    @Inject
    public HdfsConfigurationInitializer(HiveConfig config, Set<ConfigurationInitializer> configurationInitializers)
    {
        requireNonNull(config, "config is null");
        checkArgument(config.getDfsTimeout().toMillis() >= 1, "dfsTimeout must be at least 1 ms");
        checkArgument(toIntExact(config.getTextMaxLineLength().toBytes()) >= 1, "textMaxLineLength must be at least 1 byte");

        this.socksProxy = config.getMetastoreSocksProxy();
        this.ipcPingInterval = config.getIpcPingInterval();
        this.dfsTimeout = config.getDfsTimeout();
        this.dfsConnectTimeout = config.getDfsConnectTimeout();
        this.dfsConnectMaxRetries = config.getDfsConnectMaxRetries();
        this.dfsKeyProviderCacheTtlMillis = toIntExact(config.getDfsKeyProviderCacheTtl().toMillis());
        this.domainSocketPath = config.getDomainSocketPath();
        this.resourcesConfiguration = readConfiguration(config.getResourceConfigFiles());
        this.compressionCodec = config.getHiveCompressionCodec();
        this.fileSystemMaxCacheSize = config.getFileSystemMaxCacheSize();
        this.isHdfsWireEncryptionEnabled = config.isHdfsWireEncryptionEnabled();
        this.textMaxLineLength = toIntExact(config.getTextMaxLineLength().toBytes());

        this.configurationInitializers = ImmutableSet.copyOf(requireNonNull(configurationInitializers, "configurationInitializers is null"));
    }

    private static Configuration readConfiguration(List<String> resourcePaths)
    {
        Configuration result = new Configuration(false);

        for (String resourcePath : resourcePaths) {
            Configuration resourceProperties = new Configuration(false);
            resourceProperties.addResource(new Path(resourcePath));
            copy(resourceProperties, result);
        }

        return result;
    }

    public void initializeConfiguration(Configuration config)
    {
        copy(resourcesConfiguration, config);

        // this is to prevent dfs client from doing reverse DNS lookups to determine whether nodes are rack local
        config.setClass(NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY, NoOpDNSToSwitchMapping.class, DNSToSwitchMapping.class);

        if (socksProxy != null) {
            config.setClass(HADOOP_RPC_SOCKET_FACTORY_CLASS_DEFAULT_KEY, SocksSocketFactory.class, SocketFactory.class);
            config.set(HADOOP_SOCKS_SERVER_KEY, socksProxy.toString());
        }

        if (domainSocketPath != null) {
            config.setStrings(DFS_DOMAIN_SOCKET_PATH_KEY, domainSocketPath);
        }

        // only enable short circuit reads if domain socket path is properly configured
        if (!config.get(DFS_DOMAIN_SOCKET_PATH_KEY, "").trim().isEmpty()) {
            config.setBooleanIfUnset(HdfsClientConfigKeys.Read.ShortCircuit.KEY, true);
        }

        config.setInt(DFS_CLIENT_SOCKET_TIMEOUT_KEY, toIntExact(dfsTimeout.toMillis()));
        config.setInt(IPC_PING_INTERVAL_KEY, toIntExact(ipcPingInterval.toMillis()));
        config.setInt(IPC_CLIENT_CONNECT_TIMEOUT_KEY, toIntExact(dfsConnectTimeout.toMillis()));
        config.setInt(IPC_CLIENT_CONNECT_MAX_RETRIES_KEY, dfsConnectMaxRetries);

        if (isHdfsWireEncryptionEnabled) {
            config.set(HADOOP_RPC_PROTECTION, "privacy");
            config.setBoolean("dfs.encrypt.data.transfer", true);
        }

        config.setInt("fs.cache.max-size", fileSystemMaxCacheSize);

        config.setInt(DFS_CLIENT_KEY_PROVIDER_CACHE_EXPIRY_MS, dfsKeyProviderCacheTtlMillis);
        config.setInt(LineRecordReader.MAX_LINE_LENGTH, textMaxLineLength);

        configureCompression(config, compressionCodec);

        configurationInitializers.forEach(configurationInitializer -> configurationInitializer.initializeConfiguration(config));
    }

    public static void configureCompression(Configuration config, HiveCompressionCodec compressionCodec)
    {
        boolean compression = compressionCodec != HiveCompressionCodec.NONE;
        config.setBoolean(COMPRESSRESULT.varname, compression);
        config.setBoolean("mapred.output.compress", compression);
        config.setBoolean(FileOutputFormat.COMPRESS, compression);
        // For ORC
        OrcConf.COMPRESS.setString(config, compressionCodec.getOrcCompressionKind().name());
        // For RCFile and Text
        if (compressionCodec.getCodec().isPresent()) {
            config.set("mapred.output.compression.codec", compressionCodec.getCodec().get().getName());
            config.set(FileOutputFormat.COMPRESS_CODEC, compressionCodec.getCodec().get().getName());
        }
        else {
            config.unset("mapred.output.compression.codec");
            config.unset(FileOutputFormat.COMPRESS_CODEC);
        }
        // For Parquet
        config.set(ParquetOutputFormat.COMPRESSION, compressionCodec.getParquetCompressionCodec().name());
        // For SequenceFile
        config.set(FileOutputFormat.COMPRESS_TYPE, BLOCK.toString());
    }

    public static class NoOpDNSToSwitchMapping
            implements DNSToSwitchMapping
    {
        @Override
        public List<String> resolve(List<String> names)
        {
            // dfs client expects an empty list as an indication that the host->switch mapping for the given names are not known
            return ImmutableList.of();
        }

        @Override
        public void reloadCachedMappings()
        {
            // no-op
        }

        @Override
        public void reloadCachedMappings(List<String> names)
        {
            // no-op
        }
    }
}
