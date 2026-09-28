package com.db117.learnagent.transfer.application;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/** 将唯一的完整 Domain 导出包同步到用户配置的 S3 兼容对象存储。 */
@ApplicationScoped
public class R2SyncService {
    private static final String OBJECT_KEY = "learn-agent/latest.zip";
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final String BUCKET_NAME_PATTERN = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]";

    private final DataSource dataSource;

    public R2SyncService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public ConfigurationView configuration() {
        Credentials credentials = readConfiguration();
        return credentials == null
                ? new ConfigurationView(false, null, null, null, null)
                : new ConfigurationView(true, credentials.endpoint(), credentials.region(),
                credentials.bucketName(), credentials.accessKeyId());
    }

    public ConfigurationView save(ConfigurationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("对象存储配置不能为空");
        }
        String endpoint = normalizeEndpoint(request.endpoint());
        String region = request.region() == null || request.region().isBlank()
                ? "auto" : request.region().trim().toLowerCase(Locale.ROOT);
        String bucketName = request.bucketName() == null ? "" : request.bucketName().trim();
        String accessKeyId = request.accessKeyId() == null ? "" : request.accessKeyId().trim();
        String secretAccessKey = request.secretAccessKey() == null ? "" : request.secretAccessKey().trim();
        if (!region.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("S3 签名 Region 格式无效");
        }
        if (!bucketName.matches(BUCKET_NAME_PATTERN) || bucketName.length() > 63
                || bucketName.contains("..")) {
            throw new IllegalArgumentException("S3 Bucket 名称格式无效");
        }
        if (accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("Access Key ID 和 Secret Access Key 都不能为空");
        }
        if (accessKeyId.length() > 256 || secretAccessKey.length() > 1_024) {
            throw new IllegalArgumentException("S3 密钥长度无效");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO r2_sync_configuration(id, endpoint, region, bucket_name, access_key_id,
                         secret_access_key)
                     VALUES (1, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET endpoint = excluded.endpoint, region = excluded.region,
                         bucket_name = excluded.bucket_name, access_key_id = excluded.access_key_id,
                         secret_access_key = excluded.secret_access_key
                     """)) {
            statement.setString(1, endpoint);
            statement.setString(2, region);
            statement.setString(3, bucketName);
            statement.setString(4, accessKeyId);
            statement.setString(5, secretAccessKey);
            statement.executeUpdate();
            return new ConfigurationView(true, endpoint, region, bucketName, accessKeyId);
        } catch (SQLException error) {
            throw new IllegalStateException("无法保存对象存储配置", error);
        }
    }

    public RemoteStatus status() {
        Credentials credentials = readConfiguration();
        if (credentials == null) {
            return new RemoteStatus(false, null);
        }
        try (S3Client client = createClient(credentials)) {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(credentials.bucketName()).key(OBJECT_KEY).build());
            return new RemoteStatus(true, response.lastModified() == null
                    ? null : response.lastModified().toString());
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                return new RemoteStatus(true, null);
            }
            throw remoteFailure(error);
        }
    }

    public RemoteStatus upload(byte[] archive) {
        if (archive == null || archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("同步文件为空或超过 128 MB");
        }
        Credentials credentials = requireConfiguration();
        try (S3Client client = createClient(credentials)) {
            client.putObject(PutObjectRequest.builder()
                            .bucket(credentials.bucketName())
                            .key(OBJECT_KEY)
                            .contentType("application/zip")
                            .contentLength((long) archive.length)
                            .build(),
                    RequestBody.fromBytes(archive));
        } catch (S3Exception error) {
            throw remoteFailure(error);
        }
        return status();
    }

    public byte[] download() {
        Credentials credentials = requireConfiguration();
        try (S3Client client = createClient(credentials);
             ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder()
                     .bucket(credentials.bucketName()).key(OBJECT_KEY).build())) {
            Long contentLength = stream.response().contentLength();
            if (contentLength != null && contentLength > MAX_ARCHIVE_BYTES) {
                throw new IllegalArgumentException("远端同步文件超过 128 MB");
            }
            byte[] archive = stream.readNBytes((int) MAX_ARCHIVE_BYTES + 1);
            if (archive.length == 0) {
                throw new IllegalArgumentException("远端同步文件为空");
            }
            if (archive.length > MAX_ARCHIVE_BYTES) {
                throw new IllegalArgumentException("远端同步文件超过 128 MB");
            }
            return archive;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) {
                throw new RemoteObjectMissingException();
            }
            throw remoteFailure(error);
        } catch (IOException error) {
            throw new IllegalStateException("读取远端同步文件失败", error);
        }
    }

    private Credentials requireConfiguration() {
        Credentials credentials = readConfiguration();
        if (credentials == null) {
            throw new IllegalArgumentException("请先配置 S3 兼容对象存储");
        }
        return credentials;
    }

    private Credentials readConfiguration() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT endpoint, region, bucket_name, access_key_id, secret_access_key
                     FROM r2_sync_configuration WHERE id = 1
                     """);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? new Credentials(result.getString("endpoint"), result.getString("region"),
                    result.getString("bucket_name"), result.getString("access_key_id"),
                    result.getString("secret_access_key")) : null;
        } catch (SQLException error) {
            throw new IllegalStateException("无法读取对象存储配置", error);
        }
    }

    private S3Client createClient(Credentials credentials) {
        return S3Client.builder()
                .endpointOverride(URI.create(credentials.endpoint()))
                .region(Region.of(credentials.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private IllegalStateException remoteFailure(S3Exception error) {
        return new IllegalStateException("连接 S3 兼容对象存储失败，请检查网络、Endpoint、Bucket 权限和凭据", error);
    }

    private String normalizeEndpoint(String value) {
        String endpoint = value == null ? "" : value.trim();
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("S3 Endpoint URL 格式无效", error);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("S3 Endpoint URL 格式无效");
        }
        return endpoint.replaceAll("/+$", "");
    }

    /** 不包含 API Secret 的本机对象存储配置回显。
     *
     * @param configured 当前设备是否已经保存对象存储凭据
     * @param endpoint S3 兼容服务的 Endpoint URL；未配置时为空
     * @param region 签名使用的 Region；未配置时为空
     * @param bucketName 目标 Bucket 名称；未配置时为空
     * @param accessKeyId 可安全回显的 Access Key ID；未配置时为空
     */
    @RegisterForReflection
    public record ConfigurationView(
            boolean configured,
            String endpoint,
            String region,
            String bucketName,
            String accessKeyId) {
    }

    /** 用户在本机设置的 S3 兼容对象存储凭据。
     *
     * @param endpoint S3 兼容服务的 Endpoint URL
     * @param region 签名使用的 Region；Cloudflare R2 默认使用 auto
     * @param bucketName 已创建的 Bucket 名称
     * @param accessKeyId S3 Access Key ID
     * @param secretAccessKey S3 Secret Access Key；只保存在本机 SQLite
     */
    @RegisterForReflection
    public record ConfigurationRequest(
            String endpoint,
            String region,
            String bucketName,
            String accessKeyId,
            String secretAccessKey) {
        @Override
        public String toString() {
            return "ConfigurationRequest[endpoint=" + endpoint + ", region=" + region + ", bucketName=" + bucketName
                    + ", accessKeyId=" + accessKeyId + ", secretAccessKeyConfigured="
                    + (secretAccessKey != null && !secretAccessKey.isBlank()) + "]";
        }
    }

    /** 远端对象是否存在以及对象存储返回的最后修改时间。
     *
     * @param configured 本机是否已配置对象存储凭据
     * @param remoteLastModified 云端对象的最后修改时间；对象尚不存在时为空
     */
    @RegisterForReflection
    public record RemoteStatus(boolean configured, String remoteLastModified) {
    }

    /** 已配置 Bucket 中尚不存在同步对象。
     */
    public static final class RemoteObjectMissingException extends RuntimeException {
        public RemoteObjectMissingException() {
            super("远端对象存储上还没有可同步的数据");
        }
    }

    /** 只在当前进程内使用的本机凭据；错误日志不展开此值。
     *
     * @param endpoint S3 兼容服务的 Endpoint URL
     * @param region 签名使用的 Region
     * @param bucketName 目标 Bucket 名称
     * @param accessKeyId S3 Access Key ID
     * @param secretAccessKey S3 Secret Access Key
     */
    private record Credentials(
            String endpoint,
            String region,
            String bucketName,
            String accessKeyId,
            String secretAccessKey) {
        @Override
        public String toString() {
            return "Credentials[endpoint=" + endpoint + ", region=" + region + ", bucketName=" + bucketName
                    + ", accessKeyId=" + accessKeyId + ", secretAccessKeyConfigured=true]";
        }
    }
}
