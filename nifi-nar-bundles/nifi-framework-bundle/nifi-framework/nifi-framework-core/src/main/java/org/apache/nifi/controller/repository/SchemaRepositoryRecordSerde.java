/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.controller.repository;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

import org.apache.nifi.controller.queue.FlowFileQueue;
import org.apache.nifi.controller.repository.claim.ContentClaim;
import org.apache.nifi.controller.repository.claim.ResourceClaimManager;
import org.apache.nifi.controller.repository.schema.ContentClaimFieldMap;
import org.apache.nifi.controller.repository.schema.ContentClaimSchema;
import org.apache.nifi.controller.repository.schema.FlowFileSchema;
import org.apache.nifi.controller.repository.schema.RepositoryRecordFieldMap;
import org.apache.nifi.controller.repository.schema.RepositoryRecordSchema;
import org.apache.nifi.controller.repository.schema.RepositoryRecordUpdate;
import org.apache.nifi.repository.schema.FieldType;
import org.apache.nifi.repository.schema.Record;
import org.apache.nifi.repository.schema.RecordSchema;
import org.apache.nifi.repository.schema.Repetition;
import org.apache.nifi.repository.schema.SchemaRecordReader;
import org.apache.nifi.repository.schema.SchemaRecordWriter;
import org.apache.nifi.repository.schema.SimpleRecordField;
import org.wali.SerDe;
import org.wali.UpdateType;

public class SchemaRepositoryRecordSerde extends RepositoryRecordSerde implements SerDe<RepositoryRecord> {
    private static final int MAX_ENCODING_VERSION = 1;

    private final RecordSchema writeSchema = RepositoryRecordSchema.REPOSITORY_RECORD_SCHEMA_V1;
    private final RecordSchema contentClaimSchema = ContentClaimSchema.CONTENT_CLAIM_SCHEMA_V1;

    private final ResourceClaimManager resourceClaimManager;
    private volatile RecordSchema recoverySchema;

    public SchemaRepositoryRecordSerde(final ResourceClaimManager resourceClaimManager) {
        this.resourceClaimManager = resourceClaimManager;
    }

    @Override
    public void writeHeader(final DataOutputStream out) throws IOException {
        writeSchema.writeTo(out);
    }

    @Override
    public void serializeEdit(final RepositoryRecord previousRecordState, final RepositoryRecord newRecordState, final DataOutputStream out) throws IOException {
        serializeRecord(newRecordState, out);
    }

    @Override
    public void serializeRecord(final RepositoryRecord record, final DataOutputStream out) throws IOException {
        final RecordSchema schema;
        switch (record.getType()) {
            case CREATE:
            case UPDATE:
                schema = RepositoryRecordSchema.CREATE_OR_UPDATE_SCHEMA_V1;
                break;
            case CONTENTMISSING:
            case DELETE:
                schema = RepositoryRecordSchema.DELETE_SCHEMA_V1;
                break;
            case SWAP_IN:
                schema = RepositoryRecordSchema.SWAP_IN_SCHEMA_V1;
                break;
            case SWAP_OUT:
                schema = RepositoryRecordSchema.SWAP_OUT_SCHEMA_V1;
                break;
            default:
                throw new IllegalArgumentException("Received Repository Record with unknown Update Type: " + record.getType()); // won't happen.
        }

        final RepositoryRecordFieldMap fieldMap = new RepositoryRecordFieldMap(record, schema, contentClaimSchema);
        final RepositoryRecordUpdate update = new RepositoryRecordUpdate(fieldMap, RepositoryRecordSchema.REPOSITORY_RECORD_SCHEMA_V1);

        new SchemaRecordWriter().writeRecord(update, out);
    }

    @Override
    public void readHeader(final DataInputStream in) throws IOException {
        recoverySchema = RecordSchema.readFrom(in);
    }

    @Override
    public RepositoryRecord deserializeEdit(final DataInputStream in, final Map<Object, RepositoryRecord> currentRecordStates, final int version) throws IOException {
        return deserializeRecord(in, version);
    }

    @Override
    public RepositoryRecord deserializeRecord(final DataInputStream in, final int version) throws IOException {
        final SchemaRecordReader reader = SchemaRecordReader.fromSchema(recoverySchema);
        final Record updateRecord = reader.readRecord(in);

        // Top level is always going to be a "Repository Record Update" record because we need a 'Union' type record at the
        // top level that indicates which type of record we have.
        final Record record = (Record) updateRecord.getFieldValue(RepositoryRecordSchema.REPOSITORY_RECORD_UPDATE_V1);

        final String actionType = (String) record.getFieldValue(RepositoryRecordSchema.ACTION_TYPE_FIELD);
        final UpdateType updateType = UpdateType.valueOf(actionType);
        switch (updateType) {
            case CREATE:
                return createRecord(record);
            case DELETE:
                return deleteRecord(record);
            case SWAP_IN:
                return swapInRecord(record);
            case SWAP_OUT:
                return swapOutRecord(record);
            case UPDATE:
                return updateRecord(record);
            default:
                throw new IOException("Found unrecognized Update Type '" + actionType + "'");
        }
    }


    @SuppressWarnings("unchecked")
    private StandardRepositoryRecord createRecord(final Record record) {
        final StandardFlowFileRecord.Builder ffBuilder = new StandardFlowFileRecord.Builder();
        ffBuilder.id((Long) record.getFieldValue(RepositoryRecordSchema.RECORD_ID));
        ffBuilder.entryDate((Long) record.getFieldValue(FlowFileSchema.ENTRY_DATE));

        final Long lastQueueDate = (Long) record.getFieldValue(FlowFileSchema.QUEUE_DATE);
        final Long queueDateIndex = (Long) record.getFieldValue(FlowFileSchema.QUEUE_DATE_INDEX);
        ffBuilder.lastQueued(lastQueueDate, queueDateIndex);

        final Long lineageStartDate = (Long) record.getFieldValue(FlowFileSchema.LINEAGE_START_DATE);
        final Long lineageStartIndex = (Long) record.getFieldValue(FlowFileSchema.LINEAGE_START_INDEX);
        ffBuilder.lineageStart(lineageStartDate, lineageStartIndex);

        populateContentClaim(ffBuilder, record);
        ffBuilder.size((Long) record.getFieldValue(FlowFileSchema.FLOWFILE_SIZE));

        ffBuilder.addAttributes((Map<String, String>) record.getFieldValue(FlowFileSchema.ATTRIBUTES));

        final FlowFileRecord flowFileRecord = ffBuilder.build();

        final String queueId = (String) record.getFieldValue(RepositoryRecordSchema.QUEUE_IDENTIFIER);
        final FlowFileQueue queue = getFlowFileQueue(queueId);

        return new StandardRepositoryRecord(queue, flowFileRecord);
    }

    private void populateContentClaim(final StandardFlowFileRecord.Builder ffBuilder, final Record record) {
        final Object claimMap = record.getFieldValue(FlowFileSchema.CONTENT_CLAIM);
        if (claimMap == null) {
            return;
        }

        final Record claimRecord = (Record) claimMap;
        final ContentClaim contentClaim = ContentClaimFieldMap.getContentClaim(claimRecord, resourceClaimManager);
        final Long offset = ContentClaimFieldMap.getContentClaimOffset(claimRecord);

        ffBuilder.contentClaim(contentClaim);
        ffBuilder.contentClaimOffset(offset);
    }

    private RepositoryRecord updateRecord(final Record record) {
        return createRecord(record);
    }

    private RepositoryRecord deleteRecord(final Record record) {
        final Long recordId = (Long) record.getFieldValue(RepositoryRecordSchema.RECORD_ID_FIELD);
        final StandardFlowFileRecord.Builder ffBuilder = new StandardFlowFileRecord.Builder().id(recordId);
        final FlowFileRecord flowFileRecord = ffBuilder.build();

        final StandardRepositoryRecord repoRecord = new StandardRepositoryRecord((FlowFileQueue) null, flowFileRecord);
        repoRecord.markForDelete();
        return repoRecord;
    }

    private RepositoryRecord swapInRecord(final Record record) {
        final StandardRepositoryRecord repoRecord = createRecord(record);
        final String swapLocation = (String) record.getFieldValue(new SimpleRecordField(RepositoryRecordSchema.SWAP_LOCATION, FieldType.STRING, Repetition.EXACTLY_ONE));
        repoRecord.setSwapLocation(swapLocation);
        return repoRecord;
    }

    private RepositoryRecord swapOutRecord(final Record record) {
        final Long recordId = (Long) record.getFieldValue(RepositoryRecordSchema.RECORD_ID_FIELD);
        final String queueId = (String) record.getFieldValue(new SimpleRecordField(RepositoryRecordSchema.QUEUE_IDENTIFIER, FieldType.STRING, Repetition.EXACTLY_ONE));
        final String swapLocation = (String) record.getFieldValue(new SimpleRecordField(RepositoryRecordSchema.SWAP_LOCATION, FieldType.STRING, Repetition.EXACTLY_ONE));
        final FlowFileQueue queue = getFlowFileQueue(queueId);

        final FlowFileRecord flowFileRecord = new StandardFlowFileRecord.Builder()
            .id(recordId)
            .build();

        return new StandardRepositoryRecord(queue, flowFileRecord, swapLocation);
    }

    @Override
    public int getVersion() {
        return MAX_ENCODING_VERSION;
    }

}
