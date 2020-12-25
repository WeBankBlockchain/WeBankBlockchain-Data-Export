/**
 * Copyright 2020 Webank.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webank.blockchain.data.export.task;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.db.DaoTemplate;
import cn.hutool.db.Db;
import com.webank.blockchain.data.export.common.bo.contract.ContractDetail;
import com.webank.blockchain.data.export.common.bo.contract.ContractMapsInfo;
import com.webank.blockchain.data.export.common.bo.data.BlockInfoBO;
import com.webank.blockchain.data.export.common.bo.data.ContractInfoBO;
import com.webank.blockchain.data.export.common.constants.BlockConstants;
import com.webank.blockchain.data.export.common.constants.ContractConstants;
import com.webank.blockchain.data.export.common.entity.DataExportContext;
import com.webank.blockchain.data.export.common.enums.DataType;
import com.webank.blockchain.data.export.common.entity.ExportConstant;
import com.webank.blockchain.data.export.db.dao.BlockDetailInfoDAO;
import com.webank.blockchain.data.export.db.dao.BlockRawDataDAO;
import com.webank.blockchain.data.export.db.dao.BlockTxDetailInfoDAO;
import com.webank.blockchain.data.export.db.dao.DeployedAccountInfoDAO;
import com.webank.blockchain.data.export.db.dao.ESHandleDao;
import com.webank.blockchain.data.export.db.dao.SaveInterface;
import com.webank.blockchain.data.export.db.dao.TxRawDataDAO;
import com.webank.blockchain.data.export.db.dao.TxReceiptRawDataDAO;
import com.webank.blockchain.data.export.db.repository.BlockDetailInfoRepository;
import com.webank.blockchain.data.export.db.repository.BlockRawDataRepository;
import com.webank.blockchain.data.export.db.repository.BlockTaskPoolRepository;
import com.webank.blockchain.data.export.db.repository.BlockTxDetailInfoRepository;
import com.webank.blockchain.data.export.db.repository.DeployedAccountInfoRepository;
import com.webank.blockchain.data.export.db.repository.RollbackInterface;
import com.webank.blockchain.data.export.db.repository.TxRawDataRepository;
import com.webank.blockchain.data.export.db.repository.TxReceiptRawDataRepository;
import com.webank.blockchain.data.export.db.service.DataStoreService;
import com.webank.blockchain.data.export.db.service.ESStoreService;
import com.webank.blockchain.data.export.db.service.MysqlStoreService;
import com.webank.blockchain.data.export.parser.contract.ContractParser;
import com.webank.blockchain.data.export.service.BlockAsyncService;
import com.webank.blockchain.data.export.service.BlockCheckService;
import com.webank.blockchain.data.export.service.BlockDepotService;
import com.webank.blockchain.data.export.service.BlockIndexService;
import com.webank.blockchain.data.export.service.BlockPrepareService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.transport.TransportClient;
import org.fisco.bcos.sdk.client.protocol.response.BcosBlock.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GenerateCodeApplicationRunner
 *
 * @author maojiayu
 * @Description: GenerateCodeApplicationRunner
 * @date 2018年11月29日 下午4:37:38
 */

@Slf4j
@Data
public class CrawlRunner {

    private DataExportContext context;

    private long startBlockNumber;

    private BlockTaskPoolRepository blockTaskPoolRepository;
    private BlockDetailInfoRepository blockDetailInfoRepository;
    private BlockRawDataRepository blockRawDataRepository;
    private BlockTxDetailInfoRepository blockTxDetailInfoRepository;
    private TxRawDataRepository txRawDataRepository;
    private TxReceiptRawDataRepository txReceiptRawDataRepository;
    private DeployedAccountInfoRepository deployedAccountInfoRepository;

    private List<DataStoreService> dataStoreServiceList = new ArrayList<>();
    private List<RollbackInterface> rollbackOneInterfaceList = new ArrayList<>();

    private AtomicBoolean runSwitch = new AtomicBoolean(false);

    public static CrawlRunner create(DataExportContext context){
        return new CrawlRunner(context);
    }

    private CrawlRunner(DataExportContext context) {
        this.context = context;
    }


    public void run(DataExportContext context) {
        if (context.getConfig().getCrawlBatchUnit() < 1) {
            log.error("The batch unit threshold can't be less than 1!!");
            System.exit(1);
        }
        runSwitch.getAndSet(true);
        buildDataStore();
        handle();
    }

    public long getHeight(long height) {
        return Math.max(height, startBlockNumber);
    }

    /**
     * The key driving entrance of single instance depot: 1. check timeout txs and process errors; 2. produce tasks; 3.
     * consume tasks; 4. check the fork status; 5. rollback; 6. continue and circle;
     *
     */
    public void handle() {
        try{
            ContractParser.initContractMaps();
            saveContractInfo();
        }catch (Exception e) {
            log.error("initContractMaps and save Contract Info, {}", e.getMessage());
        }

        try {
            startBlockNumber = BlockIndexService.getStartBlockIndex();
            log.info("Start succeed, and the block number is {}", startBlockNumber);
        } catch (Exception e) {
            log.error("depot Error, {}", e.getMessage());
        }
        while (!Thread.currentThread().isInterrupted() && runSwitch.get()) {
            try {
                long currentChainHeight = BlockPrepareService.getCurrentBlockHeight();
                long fromHeight = getHeight(BlockPrepareService.getTaskPoolHeight());
                // control the batch unit number
                long end = fromHeight + context.getConfig().getCrawlBatchUnit() - 1;
                long toHeight = Math.min(currentChainHeight, end);
                log.info("Current depot status: {} of {}, and try to process block from {} to {}", fromHeight - 1,
                        currentChainHeight, fromHeight, toHeight);
                boolean certainty = toHeight + 1 < currentChainHeight - BlockConstants.MAX_FORK_CERTAINTY_BLOCK_NUMBER;
                if (fromHeight <= toHeight) {
                    log.info("Try to sync block number {} to {} of {}", fromHeight, toHeight, currentChainHeight);
                    BlockPrepareService.prepareTask(fromHeight, toHeight, certainty);
                } else {
                    // single circle sleep time is read from the application.properties
                    log.info("No sync block tasks to prepare, begin to sleep {} s",
                            context.getConfig().getFrequency());
                    try {
                        Thread.sleep(context.getConfig().getFrequency() * 1000);
                    }catch (InterruptedException e){
                        Thread.currentThread().interrupt();
                    }
                }
                log.info("Begin to fetch at most {} tasks", context.getConfig().getCrawlBatchUnit());
                List<Block> taskList = BlockDepotService.fetchData(context.getConfig().getCrawlBatchUnit());
                for (Block b : taskList) {
                    BlockAsyncService.handleSingleBlock(b, currentChainHeight);
                }
                if (!certainty) {
                    BlockCheckService.checkForks(currentChainHeight);
                    BlockCheckService.checkTaskCount(startBlockNumber, currentChainHeight);
                }
                BlockCheckService.checkTimeOut();
                BlockCheckService.processErrors();
            } catch (Exception e) {
                log.error("CrawlRunner run failed ", e);
                try {
                    Thread.sleep(60 * 1000L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.info("DataExportExecutor already ended ！！！");
    }

    private void saveContractInfo() {
        ContractMapsInfo mapsInfo = ContractConstants.contractMapsInfo.get();
        Map<String, ContractDetail> contractBinaryMap = mapsInfo.getContractBinaryMap();
        if (CollectionUtil.isEmpty(contractBinaryMap)) {
            return;
        }
        for (Map.Entry<String, ContractDetail> entry : contractBinaryMap.entrySet()){
            ContractInfoBO contractInfoBO = entry.getValue().getContractInfoBO();
            for (DataStoreService storeService : dataStoreServiceList) {
                storeService.storeContractInfo(contractInfoBO);
            }
        }
    }

    public void buildDataStore() {
        buildRepository();
        buildDao();
        buildESStore();
    }

    private void buildESStore(){
        if (context.getEsConfig() != null && context.getEsConfig().isEnable()) {
            TransportClient esClient = ESHandleDao.create();
            context.setEsClient(esClient);
            dataStoreServiceList.add(new ESStoreService());
        }
    }

    private void buildDao(){
        List<SaveInterface<BlockInfoBO>> saveInterfaceList = new ArrayList<>();
        MysqlStoreService mysqlStoreService = new MysqlStoreService();
        mysqlStoreService.setSaveInterfaceList(saveInterfaceList);
        dataStoreServiceList.add(mysqlStoreService);

        if (blockDetailInfoRepository != null) {
            BlockDetailInfoDAO blockDetailInfoDao = new BlockDetailInfoDAO(blockDetailInfoRepository);
            saveInterfaceList.add(blockDetailInfoDao);
        }
        if (blockTxDetailInfoRepository != null) {
            BlockTxDetailInfoDAO blockTxDetailInfoDao = new BlockTxDetailInfoDAO(blockTxDetailInfoRepository);
            saveInterfaceList.add(blockTxDetailInfoDao);
        }
        if (blockRawDataRepository != null) {
            BlockRawDataDAO blockRawDataDao = new BlockRawDataDAO(blockRawDataRepository);
            saveInterfaceList.add(blockRawDataDao);
        }
        if (txRawDataRepository != null) {
            TxRawDataDAO txRawDataDao = new TxRawDataDAO(txRawDataRepository);
            saveInterfaceList.add(txRawDataDao);
        }
        if (txReceiptRawDataRepository != null) {
            TxReceiptRawDataDAO txReceiptRawDataDao = new TxReceiptRawDataDAO(txReceiptRawDataRepository);
            saveInterfaceList.add(txReceiptRawDataDao);
        }
        if (deployedAccountInfoRepository != null){
            DeployedAccountInfoDAO deployedAccountInfoDAO = new DeployedAccountInfoDAO(deployedAccountInfoRepository);
            saveInterfaceList.add(deployedAccountInfoDAO);
        }

    }

    private void buildRepository(){
        Map<String, DaoTemplate> daoTemplateMap = buildDaoMap(context);
        blockTaskPoolRepository = new BlockTaskPoolRepository(
                daoTemplateMap.get(ExportConstant.BLOCK_TASK_POOL_DAO));
        rollbackOneInterfaceList.add(blockTaskPoolRepository);

        if (!context.getConfig().getDataTypeBlackList().contains(DataType.BLOCK_DETAIL_INFO_TABLE)) {
            blockDetailInfoRepository = new BlockDetailInfoRepository(
                    daoTemplateMap.get(ExportConstant.BLOCK_DETAIL_DAO));
            rollbackOneInterfaceList.add(blockDetailInfoRepository);
        }
        if (!context.getConfig().getDataTypeBlackList().contains(DataType.BLOCK_RAW_DATA_TABLE)) {
            blockRawDataRepository = new BlockRawDataRepository(daoTemplateMap.get(
                    ExportConstant.BLOCK_RAW_DAO));
            rollbackOneInterfaceList.add(blockRawDataRepository);

        }
        if (!context.getConfig().getDataTypeBlackList().contains(DataType.BLOCK_TX_DETAIL_INFO_TABLE)) {
            blockTxDetailInfoRepository = new BlockTxDetailInfoRepository(
                    daoTemplateMap.get(ExportConstant.BLOCK_TX_DETAIL_DAO));
            rollbackOneInterfaceList.add(blockTxDetailInfoRepository);

        }
        if (!context.getConfig().getDataTypeBlackList().contains(DataType.TX_RAW_DATA_TABLE)) {
            txRawDataRepository = new TxRawDataRepository(
                    daoTemplateMap.get(ExportConstant.TX_RAW_DAO));
            rollbackOneInterfaceList.add(txRawDataRepository);
        }
        if (!context.getConfig().getDataTypeBlackList().contains(DataType.TX_RECEIPT_RAW_DATA_TABLE)) {
            txReceiptRawDataRepository = new TxReceiptRawDataRepository(
                    daoTemplateMap.get(ExportConstant.TX_RECEIPT_RAW_DAO));
            rollbackOneInterfaceList.add(txReceiptRawDataRepository);
        }
        if (!context.getConfig().getDataTypeBlackList().contains(DataType.DEPLOYED_ACCOUNT_INFO_TABLE)) {
            deployedAccountInfoRepository = new DeployedAccountInfoRepository(
                    daoTemplateMap.get(ExportConstant.DEPLOYED_ACCOUNT_DAO));
        }

    }

    private Map<String, DaoTemplate> buildDaoMap(DataExportContext context) {
        Db db = Db.use(context.getDataSource());
        Map<String, DaoTemplate> daoTemplateMap = new ConcurrentHashMap<>();
        ExportConstant.tables.forEach(table -> {
            if (DataType.getTables(context.getConfig().getDataTypeBlackList()).contains(table)){
                return;
            }
            DaoTemplate daoTemplate = new DaoTemplate(table, "pk_id", db);
            daoTemplateMap.put(table + "_dao", daoTemplate);
        });
        return daoTemplateMap;
    }

}