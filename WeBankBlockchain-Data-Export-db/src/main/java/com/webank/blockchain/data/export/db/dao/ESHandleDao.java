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
package com.webank.blockchain.data.export.db.dao;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.webank.blockchain.data.export.common.bo.contract.ContractDetail;
import com.webank.blockchain.data.export.common.bo.contract.EventMetaInfo;
import com.webank.blockchain.data.export.common.bo.contract.MethodMetaInfo;
import com.webank.blockchain.data.export.common.bo.data.BlockInfoBO;
import com.webank.blockchain.data.export.common.bo.data.BlockTxDetailInfoBO;
import com.webank.blockchain.data.export.common.bo.data.ContractInfoBO;
import com.webank.blockchain.data.export.common.bo.data.DeployedAccountInfoBO;
import com.webank.blockchain.data.export.common.bo.data.EventBO;
import com.webank.blockchain.data.export.common.bo.data.MethodBO;
import com.webank.blockchain.data.export.common.bo.data.TxRawDataBO;
import com.webank.blockchain.data.export.common.bo.data.TxReceiptRawDataBO;
import com.webank.blockchain.data.export.common.constants.ContractConstants;
import com.webank.blockchain.data.export.common.entity.ESDataSource;
import com.webank.blockchain.data.export.common.entity.ExportConstant;
import com.webank.blockchain.data.export.db.entity.DeployedAccountInfo;
import com.webank.blockchain.data.export.db.service.ESService;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * @author wesleywang
 * @Description:
 * @date 2020/10/26
 */
public class ESHandleDao {


    public static final String BLOCK_DETAIL = "blockdetailinfo";

    public static final String BLOCK_RAW_DATA = "blockrawdata";

    public static final String TX_RAW_DATA = "txrawdata";

    public static final String DEPLOY_ACCOUNT = "deployaccountinfo";

    public static final String CONTRACT_INFO = "contractinfo";

    public static final String TX_RECEIPT_RAW_DATA = "txreceiptrawdata";

    public static final String BLOCK_TX_DETAIL = "blocktxdetailinfo";

    public static final String METHOD = "method";

    public static final String EVENT = "event";

    @SneakyThrows
    public static TransportClient create() {
        TransportClient client;
        ESDataSource esConfig = ExportConstant.threadLocal.get().getEsConfig();
        System.setProperty("es.set.netty.runtime.available.processors","false");
        Settings settings = Settings.builder()
                .put("cluster.name", esConfig.getClusterName())
                .build();
        client = new PreBuiltTransportClient(settings);
        TransportAddress node = new TransportAddress(
                InetAddress.getByName(esConfig.getIp()),
                esConfig.getPort()
        );
        client.addTransportAddress(node);

        if (!ESService.indexExists(client,BLOCK_DETAIL)){
            ESService.createIndex(client,BLOCK_DETAIL);
        }
        if (!ESService.indexExists(client,BLOCK_RAW_DATA)){
            ESService.createIndex(client,BLOCK_RAW_DATA);
        }
        if (!ESService.indexExists(client,TX_RAW_DATA)){
            ESService.createIndex(client,TX_RAW_DATA);
        }
        if (!ESService.indexExists(client,TX_RECEIPT_RAW_DATA)){
            ESService.createIndex(client,TX_RECEIPT_RAW_DATA);
        }
        if (!ESService.indexExists(client,BLOCK_TX_DETAIL)){
            ESService.createIndex(client,BLOCK_TX_DETAIL);
        }
        if (!ESService.indexExists(client,DEPLOY_ACCOUNT)){
            ESService.createIndex(client,DEPLOY_ACCOUNT);
        }
        if (!ESService.indexExists(client,CONTRACT_INFO)){
            ESService.createIndex(client,CONTRACT_INFO);
        }
        if (CollectionUtil.isNotEmpty(ExportConstant.threadLocal.get().getConfig().getContractInfoList())){
            Map<String, ContractDetail> contractBinaryMap = ContractConstants.contractMapsInfo.get().getContractBinaryMap();
            for(Map.Entry<String,ContractDetail> entry : contractBinaryMap.entrySet()) {
                ContractDetail contractDetail = entry.getValue();
                for (MethodMetaInfo methodMetaInfo : contractDetail.getMethodMetaInfos()) {
                    String index = (contractDetail.getContractInfoBO().getContractName() + methodMetaInfo.getMethodName() +
                            METHOD).toLowerCase();
                    if (!ESService.indexExists(client,index)) {
                        ESService.createIndex(client, index);
                    }
                }
                for (EventMetaInfo eventMetaInfo : contractDetail.getEventMetaInfos()) {
                    String index = (contractDetail.getContractInfoBO().getContractName() + eventMetaInfo.getEventName() +
                            EVENT).toLowerCase();
                    if (!ESService.indexExists(client,index)) {
                        ESService.createIndex(client, index);
                    }
                }
            }
        }

        return client;
    }

    public static void saveBlockInfo(BlockInfoBO blockInfoBO) {
        TransportClient client = ExportConstant.threadLocal.get().getEsClient();

        ESService.createDocument(client,
                BLOCK_DETAIL, "_doc", String.valueOf(blockInfoBO.getBlockDetailInfo().getBlockHeight()),
                blockInfoBO.getBlockDetailInfo());

        ESService.createDocument(client,
                BLOCK_RAW_DATA,"_doc", String.valueOf(blockInfoBO.getBlockRawDataBO().getBlockHeight()),
                blockInfoBO.getBlockRawDataBO());

        for (TxRawDataBO txRawDataBO : blockInfoBO.getTxRawDataBOList()) {
            ESService.createDocument(client,
                    TX_RAW_DATA,"_doc",
                    txRawDataBO.getTxHash(), txRawDataBO);
        }

        for (TxReceiptRawDataBO txReceiptRawDataBO : blockInfoBO.getTxReceiptRawDataBOList()) {
            ESService.createDocument(client,
                    TX_RECEIPT_RAW_DATA,"_doc",
                    txReceiptRawDataBO.getTxHash(),
                    txReceiptRawDataBO);
        }

        for (BlockTxDetailInfoBO blockTxDetailInfoBO : blockInfoBO.getBlockTxDetailInfoList()) {
            ESService.createDocument(client,
                    BLOCK_TX_DETAIL,"_doc",
                    blockTxDetailInfoBO.getTxHash(),
                    blockTxDetailInfoBO);
        }

        for (EventBO eventBO : blockInfoBO.getEventInfoList()) {
            ESService.createDocument(client,
                    eventBO.getTable().toLowerCase() + EVENT,
                    "_doc", eventBO.getEntity().get("tx_hash").toString(), eventBO);
        }

        for (MethodBO methodBO : blockInfoBO.getMethodInfoList()) {
            ESService.createDocument(client,
                    methodBO.getTable().toLowerCase() + METHOD,
                    "_doc", methodBO.getEntity().get("tx_hash").toString(), methodBO);
        }
    }

    public static void saveContractInfo(ContractInfoBO contractInfoBO) {
        TransportClient client = ExportConstant.threadLocal.get().getEsClient();
        ESService.createDocument(client,
                CONTRACT_INFO, "_doc",contractInfoBO);
    }

}
