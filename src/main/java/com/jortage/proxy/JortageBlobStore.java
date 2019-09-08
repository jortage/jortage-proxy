package com.jortage.proxy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.domain.BlobBuilder;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.ContainerAccess;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.MutableBlobMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.internal.MutableBlobMetadataImpl;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.CreateContainerOptions;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.blobstore.util.ForwardingBlobStore;
import org.jclouds.domain.Location;
import org.jclouds.io.Payload;
import org.jclouds.io.payloads.FilePayload;

import com.google.common.base.Objects;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.common.io.ByteStreams;

public class JortageBlobStore extends ForwardingBlobStore {
	private final String identity;
	private final String bucket;
	private final Map<String, String> paths;

	public JortageBlobStore(BlobStore blobStore, String bucket, String identity, Map<String, String> paths) {
		super(blobStore);
		this.bucket = bucket;
		this.identity = identity;
		this.paths = paths;
	}

	private String buildKey(String name) {
		return JortageProxy.buildKey(identity, name);
	}

	private void checkContainer(String container) {
		if (!Objects.equal(container, identity)) {
			throw new IllegalArgumentException("Bucket name must match your access ID");
		}
	}

	private String map(String container, String name) {
		checkContainer(container);
		String hash = paths.get(buildKey(name));
		if (hash == null) throw new IllegalArgumentException("Not found");
		return JortageProxy.hashToPath(hash);
	}

	@Override
	public BlobStoreContext getContext() {
		return delegate().getContext();
	}

	@Override
	public BlobBuilder blobBuilder(String name) {
		return delegate().blobBuilder(name);
	}

	@Override
	public Blob getBlob(String container, String name) {
		return delegate().getBlob(bucket, map(container, name));
	}

	@Override
	public Blob getBlob(String container, String name, GetOptions getOptions) {
		return delegate().getBlob(bucket, map(container, name), getOptions);
	}

	@Override
	public void downloadBlob(String container, String name, File destination) {
		delegate().downloadBlob(bucket, map(container, name), destination);
	}

	@Override
	public void downloadBlob(String container, String name, File destination, ExecutorService executor) {
		delegate().downloadBlob(bucket, map(container, name), destination, executor);
	}

	@Override
	public InputStream streamBlob(String container, String name) {
		return delegate().streamBlob(bucket, map(container, name));
	}

	@Override
	public InputStream streamBlob(String container, String name, ExecutorService executor) {
		return delegate().streamBlob(bucket, map(container, name), executor);
	}

	@Override
	public BlobAccess getBlobAccess(String container, String name) {
		return BlobAccess.PUBLIC_READ;
	}

	@Override
	public PageSet<? extends StorageMetadata> list() {
		throw new UnsupportedOperationException();
	}

	@Override
	public PageSet<? extends StorageMetadata> list(String container) {
		throw new UnsupportedOperationException();
	}

	@Override
	public PageSet<? extends StorageMetadata> list(String container,
			ListContainerOptions options) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ContainerAccess getContainerAccess(String container) {
		return ContainerAccess.PUBLIC_READ;
	}

	@Override
	public boolean blobExists(String container, String name) {
		return delegate().blobExists(bucket, map(container, name));
	}

	@Override
	public BlobMetadata blobMetadata(String container, String name) {
		return delegate().blobMetadata(bucket, map(container, name));
	}

	@Override
	public boolean directoryExists(String container, String directory) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getMaximumNumberOfParts() {
		return delegate().getMaximumNumberOfParts();
	}

	@Override
	public long getMinimumMultipartPartSize() {
		return delegate().getMinimumMultipartPartSize();
	}

	@Override
	public long getMaximumMultipartPartSize() {
		return delegate().getMaximumMultipartPartSize();
	}

	@Override
	public String putBlob(String container, Blob blob) {
		checkContainer(container);
		File tempFile = null;
		try {
			File f = File.createTempFile("jortage-proxy-", ".dat");
			tempFile = f;
			String contentType = blob.getPayload().getContentMetadata().getContentType();
			String hash;
			try (InputStream is = blob.getPayload().openStream();
					FileOutputStream fos = new FileOutputStream(f)) {
				HashingOutputStream hos = new HashingOutputStream(Hashing.sha512(), fos);
				ByteStreams.copy(is, hos);
				hash = hos.hash().toString();
			}
			try (Payload payload = new FilePayload(f)) {
				payload.getContentMetadata().setContentType(contentType);
				if (delegate().blobExists(bucket, JortageProxy.hashToPath(hash))) {
					String etag = delegate().blobMetadata(bucket, JortageProxy.hashToPath(hash)).getETag();
					paths.put(buildKey(blob.getMetadata().getName()), hash);
					return etag;
				}
				Blob blob2 = blobBuilder(JortageProxy.hashToPath(hash))
						.payload(payload)
						.userMetadata(blob.getMetadata().getUserMetadata())
						.build();
				String etag = delegate().putBlob(bucket, blob2, new PutOptions().setBlobAccess(BlobAccess.PUBLIC_READ).multipart());
				paths.put(buildKey(blob.getMetadata().getName()), hash);
				return etag;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			if (tempFile != null) tempFile.delete();
		}
	}

	@Override
	public MultipartUpload initiateMultipartUpload(String container, BlobMetadata blobMetadata, PutOptions options) {
		MutableBlobMetadata mbm = new MutableBlobMetadataImpl(blobMetadata);
		mbm.setContainer(bucket);
		mbm.setName(map(blobMetadata.getContainer(), blobMetadata.getName()));
		return delegate().initiateMultipartUpload(bucket, mbm, options);
	}

	@Override
	public void abortMultipartUpload(MultipartUpload mpu) {
		delegate().abortMultipartUpload(mpu);
	}

	@Override
	public String completeMultipartUpload(MultipartUpload mpu, List<MultipartPart> parts) {
		return delegate().completeMultipartUpload(mpu, parts);
	}

	@Override
	public MultipartPart uploadMultipartPart(MultipartUpload mpu, int partNumber, Payload payload) {
		return delegate().uploadMultipartPart(mpu, partNumber, payload);
	}

	@Override
	public List<MultipartPart> listMultipartUpload(MultipartUpload mpu) {
		return delegate().listMultipartUpload(mpu);
	}

	@Override
	public List<MultipartUpload> listMultipartUploads(String container) {
		return delegate().listMultipartUploads(bucket);
	}

	@Override
	public String putBlob(String containerName, Blob blob, PutOptions putOptions) {
		return putBlob(containerName, blob);
	}

	@Override
	public boolean createContainerInLocation(Location location,
			String container) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public boolean createContainerInLocation(Location location,
			String container, CreateContainerOptions createContainerOptions) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void setContainerAccess(String container, ContainerAccess
			containerAccess) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void clearContainer(String container) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void clearContainer(String container, ListContainerOptions options) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void deleteContainer(String container) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public boolean deleteContainerIfEmpty(String container) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void createDirectory(String container, String directory) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void deleteDirectory(String container, String directory) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public String copyBlob(String fromContainer, String fromName, String toContainer, String toName,
			CopyOptions options) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void removeBlob(String container, String name) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void removeBlobs(String container, Iterable<String> iterable) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

	@Override
	public void setBlobAccess(String container, String name,
			BlobAccess access) {
		throw new UnsupportedOperationException("Read-only BlobStore");
	}

}