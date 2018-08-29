/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
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
 * </p>
 */

package io.shardingsphere.transaction.listener.base;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import io.shardingsphere.core.constant.transaction.TransactionType;
import io.shardingsphere.transaction.event.base.SagaSQLExecutionEvent;
import io.shardingsphere.transaction.event.base.SagaTransactionEvent;
import io.shardingsphere.transaction.listener.ShardingTransactionListenerAdapter;
import io.shardingsphere.transaction.manager.ShardingTransactionManagerRegistry;
import io.shardingsphere.transaction.manager.base.BASETransactionManager;
import io.shardingsphere.transaction.manager.base.SagaTransactionManager;
import io.shardingsphere.transaction.manager.base.servicecomb.SagaDefinitionBuilder;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saga transaction listener.
 *
 * @author yangyi
 */
@Slf4j
public final class SagaTransactionListener extends ShardingTransactionListenerAdapter<SagaTransactionEvent> {
    
    private final BASETransactionManager<SagaTransactionEvent> transactionManager = (SagaTransactionManager) ShardingTransactionManagerRegistry.getInstance().getShardingTransactionManager(TransactionType.BASE);
    
    private final Map<String, SagaDefinitionBuilder> sagaDefinitionBuilderMap = new ConcurrentHashMap<>();
    
    @Subscribe
    @AllowConcurrentEvents
    @Override
    public void listen(final SagaTransactionEvent transactionEvent) throws SQLException {
        switch (transactionEvent.getOperationType()) {
            case COMMIT:
                try {
                    transactionEvent.setSagaJson(sagaDefinitionBuilderMap.remove(transactionManager.getTransactionId()).build());
                    doTransaction(transactionManager, transactionEvent);
                } catch (JsonProcessingException e) {
                    // shouldn't really happen, but is declared as possibility so:
                    log.error("saga transaction", transactionManager.getTransactionId(), "commit failed, caused by json build exception: ", e);
                    return;
                }
                break;
            case ROLLBACK:
                sagaDefinitionBuilderMap.remove(transactionManager.getTransactionId());
                doTransaction(transactionManager, transactionEvent);
                break;
            case BEGIN:
                doTransaction(transactionManager, transactionEvent);
                sagaDefinitionBuilderMap.put(transactionManager.getTransactionId(), new SagaDefinitionBuilder());
                break;
            default:
        }
    }
    
    /**
     * listen Saga Sql execution event.
     *
     * @param sqlExecutionEvent saga sql execution event
     */
    @Subscribe
    @AllowConcurrentEvents
    public void listenSQLExecutionEvent(final SagaSQLExecutionEvent sqlExecutionEvent) {
        switch (sqlExecutionEvent.getEventType()) {
            case BEFORE_EXECUTE:
                sagaDefinitionBuilderMap.get(sqlExecutionEvent.getTransactionId()).switchParents();
                break;
            case EXECUTE_SUCCESS:
                //TODO generate revert sql by sql and params in event
                sagaDefinitionBuilderMap.get(sqlExecutionEvent.getTransactionId()).addChildRequest(
                        sqlExecutionEvent.getId(),
                        sqlExecutionEvent.getDataSource(),
                        sqlExecutionEvent.getSqlUnit().getSql(),
                        sqlExecutionEvent.getParameters(),
                        "", null);
                break;
            case EXECUTE_FAILURE:
            default:
        }
    }
}
