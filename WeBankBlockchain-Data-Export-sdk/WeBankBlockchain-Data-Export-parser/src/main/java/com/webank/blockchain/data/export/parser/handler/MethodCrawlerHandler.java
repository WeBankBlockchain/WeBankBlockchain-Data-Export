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
package com.webank.blockchain.data.export.parser.handler;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.google.common.collect.Maps;
import com.webank.blockchain.data.export.common.bo.contract.FieldVO;
import com.webank.blockchain.data.export.common.bo.contract.MethodMetaInfo;
import com.webank.blockchain.data.export.common.bo.data.BlockMethodInfo;
import com.webank.blockchain.data.export.common.bo.data.BlockTxDetailInfoBO;
import com.webank.blockchain.data.export.common.bo.data.MethodBO;
import com.webank.blockchain.data.export.common.bo.data.TxRawDataBO;
import com.webank.blockchain.data.export.common.bo.data.TxReceiptRawDataBO;
import com.webank.blockchain.data.export.common.entity.ContractInfo;
import com.webank.blockchain.data.export.common.entity.ExportConfig;
import com.webank.blockchain.data.export.common.entity.ExportConstant;
import com.webank.blockchain.data.export.common.entity.TableSQL;
import com.webank.blockchain.data.export.common.enums.IgnoreBasicDataParam;
import com.webank.blockchain.data.export.common.tools.DateUtils;
import com.webank.blockchain.data.export.common.tools.JacksonUtils;
import com.webank.blockchain.data.export.common.tools.MethodUtils;
import com.webank.blockchain.data.export.parser.service.TransactionService;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.client.protocol.model.JsonTransactionResponse;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosBlock.Block;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosBlock.TransactionObject;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosBlock.TransactionResult;
import org.fisco.bcos.sdk.v3.codec.datatypes.Type;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.transaction.codec.decode.TransactionDecoderInterface;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import org.fisco.bcos.sdk.v3.utils.Numeric;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * MethodCrawlerHandler
 *
 * @Description: MethodCrawlerHandler
 * @author maojiayu
 * @data Jul 3, 2019 10:17:15 AM
 *
 */
@Slf4j
public class MethodCrawlerHandler {


    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static BlockMethodInfo crawl(Block block, Map<String, String> txHashContractAddressMapping) throws IOException {
        BlockMethodInfo blockMethodInfo = new BlockMethodInfo();
        List<BlockTxDetailInfoBO> blockTxDetailInfoList = new ArrayList<>();
        List<TxRawDataBO> txRawDataBOList = new ArrayList<>();
        List<TxReceiptRawDataBO> txReceiptRawDataBOList = new ArrayList<>();
        List<MethodBO> methodInfoList = new ArrayList();
        List<TransactionResult> transactionResults = block.getTransactions();
        Map<String, String> txHashContractNameMapping = new HashMap<>();
        if (transactionResults != null) {
            for (TransactionResult result : transactionResults) {
                TransactionObject to = (TransactionObject) result;
                JsonTransactionResponse transaction = to.get();
                TransactionReceipt receipt = ExportConstant.getCurrentContext().getClient()
                        .getTransactionReceipt(transaction.getHash()).getTransactionReceipt();
                String contractAddress = "";
                if (receipt != null) {
                    TxRawDataBO txRawDataBO = getTxRawDataBO(block, transaction, receipt);
                    contractAddress = receipt.getContractAddress();
                    TxReceiptRawDataBO txReceiptRawDataBO = getTxReceiptRawDataBO(block, receipt, contractAddress);
                    txRawDataBOList.add(txRawDataBO);
                    txReceiptRawDataBOList.add(txReceiptRawDataBO);
                }
                Optional<String> contractName = TransactionService.getContractNameByTransaction(block.getNumber(), transaction, txHashContractAddressMapping);
                if (!contractName.isPresent()) {
                    continue;
                }
                MethodMetaInfo methodMetaInfo = TransactionService.getMethodMetaInfo(transaction, contractName.get());
                if (methodMetaInfo == null) {
                    continue;
                }
                if (receipt != null) {
                    Map<String, ContractInfo> contractAbiMap = ExportConstant.getCurrentContext().getContractInfoMap();
                    String abi = contractAbiMap.get(contractName.get()).getAbi();
                    if (abi == null) {
                        continue;
                    }
                    MethodBO methodBO = parseMethod(block, methodMetaInfo, receipt, abi, contractAddress);
                    if (methodBO != null) {
                        methodInfoList.add(methodBO);
                    }
                    // get block tx detail info
                    BlockTxDetailInfoBO blockTxDetailInfo =
                            getBlockTxDetailInfo(block, transaction, receipt, methodMetaInfo);
                    blockTxDetailInfoList.add(blockTxDetailInfo);
                    txHashContractNameMapping.putIfAbsent(blockTxDetailInfo.getTxHash(),
                            blockTxDetailInfo.getContractName());
                }
            }
        }
        blockMethodInfo.setBlockTxDetailInfoList(blockTxDetailInfoList)
                .setMethodInfoList(methodInfoList)
                .setTxHashContractNameMapping(txHashContractNameMapping)
                .setTxRawDataBOList(txRawDataBOList)
                .setTxReceiptRawDataBOList(txReceiptRawDataBOList);
        return blockMethodInfo;

    }

    public static MethodBO parseMethod(Block block, MethodMetaInfo methodMetaInfo, TransactionReceipt receipt,
                                       String abi, String contractAddress){
        TransactionDecoderInterface decoder = ExportConstant.getCurrentContext().getDecoder();
        ExportConfig config = ExportConstant.getCurrentContext().getConfig();
        MethodBO methodBO = null;
        try {
            List<Object> params = MethodUtils.decodeMethodInput(abi, methodMetaInfo.getOriginName(), receipt,
                    ExportConstant.getCurrentContext().getClient());
            if(CollectionUtil.isEmpty(params)) {
                return null;
            }
            methodBO = new MethodBO();
            Map<String, Object> entity = Maps.newHashMap();
            entity.put("block_time_stamp", new Date(block.getTimestamp()));
            entity.put("tx_hash", receipt.getTransactionHash());
            entity.put("contract_address", contractAddress);
            entity.put("block_height", Long.parseLong(receipt.getBlockNumber()));
            entity.put("method_status", receipt.getStatus());
            methodBO.setEntity(entity);
            methodBO.setToAddress(receipt.getTo());
            methodBO.setTable(TableSQL.getTableName(methodMetaInfo.getContractName(), methodMetaInfo.getMethodName() + "_method"));
            TransactionResponse response;
            if (!CollectionUtil.isEmpty(methodMetaInfo.getOutputList())) {
                response = decoder.decodeReceiptWithValues(abi, methodMetaInfo.getOriginName(), receipt);
                List<Type> returns = response.getResults();
                int i = 0;
                for (FieldVO fieldVO : methodMetaInfo.getOutputList()) {
                    if (CollectionUtil.isNotEmpty(config.getIgnoreParam())
                            && config.getIgnoreParam().containsKey(methodMetaInfo.getContractName())){
                        Map<String,List<String>> ignoreParamMap = config.getIgnoreParam().get(methodMetaInfo.getContractName());
                        if (ignoreParamMap.containsKey(methodMetaInfo.getMethodName())){
                            if (ignoreParamMap.get(methodMetaInfo.getMethodName()).contains(fieldVO.getJavaName())){
                                i++;
                                continue;
                            }
                        }
                    }
                    if (returns.get(i).getValue() instanceof java.util.List){
                        entity.put(fieldVO.getSqlName(), JSONUtil.toJsonStr(returns.get(i)));
                        continue;
                    }
                    entity.put(fieldVO.getSqlName(), returns.get(i++).getValue());
                }
            }
            List<FieldVO> fieldVOS = methodMetaInfo.getFieldsList();
            if (CollectionUtil.isEmpty(fieldVOS)) {
                return methodBO;
            }
            for (int i = 0; i < fieldVOS.size(); i++) {
                if (CollectionUtil.isNotEmpty(config.getIgnoreParam())
                        && config.getIgnoreParam().containsKey(methodMetaInfo.getContractName())){
                    Map<String,List<String>> ignoreParamMap = config.getIgnoreParam().get(methodMetaInfo.getContractName());
                    if (ignoreParamMap.containsKey(methodMetaInfo.getMethodName())){
                        if (ignoreParamMap.get(methodMetaInfo.getMethodName()).contains(fieldVOS.get(i).getSolidityName())){
                            continue;
                        }
                    }
                }
                if (params.get(i) instanceof List){
                    entity.put(fieldVOS.get(i).getSqlName(), JSONUtil.toJsonStr(params.get(i)));
                    continue;
                }
                entity.put(fieldVOS.get(i).getSqlName(), params.get(i));
            }
        } catch (Exception e) {
            log.error("decoder.decodeEvents failed", e);
        }
        return methodBO;
    }

    public static BlockTxDetailInfoBO getBlockTxDetailInfo(Block block, JsonTransactionResponse transaction,
                                                           TransactionReceipt receipt, MethodMetaInfo methodMetaInfo) {
        BlockTxDetailInfoBO blockTxDetailInfo = new BlockTxDetailInfoBO();
        blockTxDetailInfo.setBlockHash(block.getHash()).setBlockHeight(receipt.getBlockNumber())
                .setContractName(methodMetaInfo.getContractName())
                .setMethodName(methodMetaInfo.getMethodName())
                .setTxFrom(transaction.getFrom()).setTxTo(transaction.getTo()).setTxHash(receipt.getTransactionHash())
                .setBlockTimeStamp(new Date(block.getTimestamp()));
        return blockTxDetailInfo;
    }

    public static TxRawDataBO getTxRawDataBO(Block block, JsonTransactionResponse transaction, TransactionReceipt receipt) {
        ExportConfig config = ExportConstant.getCurrentContext().getConfig();
        Map<String, List<String>> ignoreBasicDataTableParam = config.getIgnoreBasicDataTableParam();

        TxRawDataBO txRawDataBO = new TxRawDataBO();
        txRawDataBO.setBlockHash(block.getHash())
                .setBlockHeight(Numeric.decodeQuantity((receipt.getBlockNumber())).longValue())
                .setBlockTimeStamp(new Date(block.getTimestamp()))
                .setTxHash(receipt.getTransactionHash());
        if (!ignoreBasicDataTableParam.containsKey(IgnoreBasicDataParam.IgnoreBasicDataTable.TX_RAW_DATA_TABLE.name())) {
            txRawDataBO
//                    .setTxIndex(transaction.getTransactionIndex())
                    .setFrom(transaction.getFrom())
                    .setInput(transaction.getInput())
                    .setNonce(transaction.getNonce())
                    .setChainId(transaction.getChainID())
                    .setTo(transaction.getTo());
        } else {
            List<String> params = ignoreBasicDataTableParam.get(IgnoreBasicDataParam.IgnoreBasicDataTable.TX_RAW_DATA_TABLE.name());
            if (!params.contains(IgnoreBasicDataParam.TxRawDataParams.FROM.name())) {
                txRawDataBO.setFrom(transaction.getFrom());
            }
//            if (!params.contains(IgnoreBasicDataParam.TxRawDataParams.GAS.name())) {
//                txRawDataBO.setGas(transaction.getGas());
//            }
//            if (!params.contains(IgnoreBasicDataParam.TxRawDataParams.GAS_PRICE.name())) {
//                txRawDataBO.setGasPrice(transaction.getGasPrice());
//            }
            if (!params.contains(IgnoreBasicDataParam.TxRawDataParams.INPUT.name())) {
                txRawDataBO.setInput(transaction.getInput());
            }
            if (!params.contains(IgnoreBasicDataParam.TxRawDataParams.NONCE.name())) {
                txRawDataBO.setNonce(transaction.getNonce());
            }
            if (!params.contains(IgnoreBasicDataParam.TxRawDataParams.TO.name())) {
                txRawDataBO.setTo(transaction.getTo());
            }
//            if (!params.contains(IgnoreBasicDataParam.TxRawDataParams.VALUE.name())) {
//                txRawDataBO.setValue(transaction.getValue());
//            }
        }
        return txRawDataBO;
    }

    public static TxReceiptRawDataBO getTxReceiptRawDataBO(Block block, TransactionReceipt receipt, String contractAddress) {
        ExportConfig config = ExportConstant.getCurrentContext().getConfig();
        Map<String, List<String>> ignoreBasicDataTableParam = config.getIgnoreBasicDataTableParam();

        TxReceiptRawDataBO txReceiptRawDataBO = new TxReceiptRawDataBO();
        txReceiptRawDataBO.setBlockHash(block.getHash())
                .setBlockHeight(Numeric.decodeQuantity((receipt.getBlockNumber())).longValue())
                .setBlockTimeStamp(new Date(block.getTimestamp()))
                .setTxHash(receipt.getTransactionHash())
                .setContractAddress(contractAddress);
        if (!ignoreBasicDataTableParam.containsKey(IgnoreBasicDataParam.IgnoreBasicDataTable.TX_RECEIPT_RAW_DATA_TABLE.name())) {
            txReceiptRawDataBO.setFrom(receipt.getFrom())
                    .setGasUsed(receipt.getGasUsed())
                    .setInput(receipt.getInput())
                    .setLogs(JacksonUtils.toJson(receipt.getLogEntries()))
                    .setMessage(receipt.getMessage())
                    .setOutput(receipt.getOutput())
                    .setStatus(String.valueOf(receipt.getStatus()))
                    .setMessage(receipt.getMessage())
//                    .setLogsBloom(JacksonUtils.toJson(receipt.getLogsBloom()))
//                    .setRoot(receipt.getRoot())
                    .setTo(receipt.getTo())
//                    .setTxIndex(receipt.getTransactionIndex())
                    .setTxProof(JacksonUtils.toJson(receipt.getTransactionProof()))
                    .setReceiptProof(JacksonUtils.toJson(receipt.getReceiptProof()));
        }else {
            List<String> params = ignoreBasicDataTableParam.get(IgnoreBasicDataParam.IgnoreBasicDataTable.TX_RECEIPT_RAW_DATA_TABLE.name());
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.FROM.name())) {
                txReceiptRawDataBO.setFrom(receipt.getFrom());
            }
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.GAS_USED.name())) {
                txReceiptRawDataBO.setGasUsed(receipt.getGasUsed());
            }
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.LOGS.name())) {
                txReceiptRawDataBO.setLogs(JacksonUtils.toJson(receipt.getLogEntries()));
            }
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.INPUT.name())) {
                txReceiptRawDataBO.setInput(receipt.getInput());
            }
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.MESSAGE.name())) {
                txReceiptRawDataBO.setMessage(receipt.getMessage());
            }
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.OUTPUT.name())) {
                txReceiptRawDataBO.setOutput(receipt.getOutput());
            }
//            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.LOGS_BLOOM.name())) {
//                txReceiptRawDataBO.setLogsBloom(JacksonUtils.toJson(receipt.getLogsBloom()));
//            }
//            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.ROOT.name())) {
//                txReceiptRawDataBO.setMessage(receipt.getRoot());
//            }
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.TO.name())) {
                txReceiptRawDataBO.setTo(receipt.getTo());
            }
//            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.TX_INDEX.name())) {
//                txReceiptRawDataBO.setTxIndex(receipt.getTransactionIndex());
//            }
//            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.TX_PROOF.name())) {
//                txReceiptRawDataBO.setTxProof(JacksonUtils.toJson(receipt.getTxProof()));
//            }
            if (!params.contains(IgnoreBasicDataParam.TxReceiptRawDataParams.RECEIPT_PROOF.name())) {
                txReceiptRawDataBO.setReceiptProof(JacksonUtils.toJson(receipt.getReceiptProof()));
            }
        }
        return txReceiptRawDataBO;
    }

}
