package com.prifender.des.adapter.relational.teradata;

import java.util.Date;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import com.prifender.des.DataExtractionServiceException;
import com.prifender.des.controller.DataExtractionThread;
import com.prifender.des.model.ConnectionParam;
import com.prifender.des.model.DataExtractionAttribute;
import com.prifender.des.model.DataExtractionJob;
import com.prifender.des.model.DataExtractionSpec;
import com.prifender.des.model.DataSource;
import com.prifender.des.model.Problem;
import com.prifender.des.util.CommonUtil;
import com.prifender.encryption.service.client.EncryptionServiceClient;
import com.prifender.messaging.api.MessagingConnection;
import com.prifender.messaging.api.MessagingConnectionFactory;
import com.prifender.messaging.api.MessagingQueue;

public class TeraDataDataExtractionExecutor extends DataExtractionThread {
	private final TeraDataDataExtractionETLJobExecutor etlJobUtil;

	DataSource ds;
	int dataSize;
	int chunkSize;
	String adapterHome;
	DataExtractionSpec.ScopeEnum scope;
	MessagingConnectionFactory messaging;
	EncryptionServiceClient encryptionServiceClient;
	private static final String JOB_NAME = "local_project.prifender_teradata_v1_0_1.Prifender_Teradata_v1";
	private static final String DEPENDENCY_JAR = "prifender_teradata_v1_0_1.jar";

	public TeraDataDataExtractionExecutor(DataSource ds, final DataExtractionSpec spec, final DataExtractionJob job,
			final int dataSize, final String adapterHome,MessagingConnectionFactory messaging,EncryptionServiceClient encryptionServiceClient) throws DataExtractionServiceException {
		super(spec, job, dataSize);
		etlJobUtil = new TeraDataDataExtractionETLJobExecutor(adapterHome);
		this.ds = ds;
		this.dataSize = dataSize;
		this.adapterHome = adapterHome;

		if (spec.getScope() == null) {
			spec.setScope(DataExtractionSpec.ScopeEnum.ALL);
		}
		this.scope = spec.getScope();
		this.chunkSize = this.dataSize;
		this.messaging=messaging;
		this.encryptionServiceClient=encryptionServiceClient;
		if (spec.getScope().equals(DataExtractionSpec.ScopeEnum.SAMPLE)) {
			if (spec.getSampleSize() == null) {
				throw new DataExtractionServiceException(
						new Problem().code("Meta data error").message("sampleSize value not found"));
			}
			this.chunkSize = this.dataSize < spec.getSampleSize() ? this.dataSize : spec.getSampleSize();
		}
	}

	@Override
	public void run() {

		final String queueName = "DES-" + this.job.getId();

		synchronized (this.job) {
			this.job.setTimeStarted(DATE_FORMAT.format(new Date()));
			this.job.setOutputMessagingQueue(queueName);
			this.job.setObjectCount(this.chunkSize);
			this.job.setObjectsExtracted(0);
		}
		try( final MessagingConnection messagingServiceConnection = this.messaging.connect() ){
			 final String scheduleQueueName = CommonUtil.getScheduleQueueName();
			 final MessagingQueue queue = messagingServiceConnection.queue( scheduleQueueName );
			StringBuffer stringBuffer = new StringBuffer();
			for (final DataExtractionAttribute attribute : spec.getAttributes()) {
				stringBuffer.append(attribute.getName()).append(",");
			}

			String dataSourceColumnNames = removeLastChar(stringBuffer.toString());
			final String dataSourceHost = getConnectionParam(ds, "Host");
			final String dataSourcePort = getConnectionParam(ds, "Port");
			final String dataSourceUser = encryptionServiceClient.encrypt(getConnectionParam(ds, "UserName"));
			final String dataSourcePassword = encryptionServiceClient.encrypt(getConnectionParam(ds, "Password"));
			String databaseName = getConnectionParam(ds, "DatabaseName");
			String tableName = spec.getCollection();
			String[] databaseTable = StringUtils.split(tableName, ".");
			if (databaseTable.length == 2 ) {
				databaseName = databaseTable[0];
				tableName = databaseTable[1];
			}

			 int chunkCount = 0;
			 if(this.scope.equals(DataExtractionSpec.ScopeEnum.ALL)){
				   int sampleSizeForChunk = CommonUtil.getSampleSize(this.chunkSize);
				   int calculatedChunkSize = (this.chunkSize / sampleSizeForChunk ); 
					if(calculatedChunkSize > 1){
						for(int i=0;i<calculatedChunkSize;i++){
							int offset = Integer.valueOf(sampleSizeForChunk*i);
						Map<String, String> contextParams = etlJobUtil.getContextParams(adapterHome, JOB_NAME, dataSourceUser,
								dataSourcePassword, tableName, dataSourceColumnNames, dataSourceHost, dataSourcePort,
								databaseName, queueName, String.valueOf(offset), String.valueOf(sampleSizeForChunk),
								String.valueOf(DataExtractionSpec.ScopeEnum.SAMPLE), String.valueOf(sampleSizeForChunk));
						chunkCount++;
						CommonUtil.postMessage(queue,contextParams,JOB_NAME,this.job.getId(),DEPENDENCY_JAR,scheduleQueueName,ds.getType(), 
								calculatedChunkSize,chunkCount);
					}
				}else{
					int offset=0;
					Map<String, String> contextParams = etlJobUtil.getContextParams(adapterHome, JOB_NAME, dataSourceUser,
							dataSourcePassword, tableName, dataSourceColumnNames, dataSourceHost, dataSourcePort,
							databaseName, queueName, String.valueOf(offset), String.valueOf(this.chunkSize),
							String.valueOf(DataExtractionSpec.ScopeEnum.SAMPLE), String.valueOf(this.chunkSize));
					chunkCount++;
					CommonUtil.postMessage(queue,contextParams,JOB_NAME,this.job.getId(),DEPENDENCY_JAR,scheduleQueueName,ds.getType(),
							1,chunkCount);
				
				}
			 }else{
				 int offset=0;
				 Map<String, String> contextParams = etlJobUtil.getContextParams(adapterHome, JOB_NAME, dataSourceUser,
							dataSourcePassword, tableName, dataSourceColumnNames, dataSourceHost, dataSourcePort,
							databaseName, queueName, String.valueOf(offset), String.valueOf(this.chunkSize),
							String.valueOf(DataExtractionSpec.ScopeEnum.SAMPLE), String.valueOf(this.chunkSize));
				 chunkCount++;	
				 CommonUtil.postMessage(queue,contextParams,JOB_NAME,this.job.getId(),DEPENDENCY_JAR,scheduleQueueName,ds.getType(), 
							1,chunkCount);
				
			 }
			 synchronized (this.job) {
					this.job.setChunksCount(chunkCount); 
				}
		} catch (Exception e) {
			synchronized (this.job) {
				this.job.setState(DataExtractionJob.StateEnum.FAILED);
				this.job.setFailureMessage(e.getMessage());
			}

		}
	}

	private String removeLastChar(String str) {
		return str.substring(0, str.length() - 1);
	}

	protected final String getConnectionParam(final DataSource ds, final String param) {
		if (ds == null) {
			throw new IllegalArgumentException();
		}

		if (param == null) {
			throw new IllegalArgumentException();
		}

		for (final ConnectionParam cp : ds.getConnectionParams()) {
			if (cp.getId().equals(param)) {
				return cp.getValue();
			}
		}

		return null;
	}
}