package com.webank.blockchain.data.export.common.client;

import com.webank.blockchain.data.export.common.entity.ChainInfo;
import com.webank.blockchain.data.export.common.entity.ExportConstant;
import com.webank.blockchain.data.export.common.tools.ClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosBlock;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;

import java.math.BigInteger;

/**
 * @author wesleywang
 * @Description:
 * @date 2021/2/25
 */
@Slf4j
public class ChannelClient implements ChainClient {

    private Client client;

    public ChannelClient() throws Exception {
        ChainInfo chainInfo = ExportConstant.getCurrentContext().getChainInfo();
        try {
            client = ClientUtil.getClient(chainInfo);
        } catch (Exception e) {
            log.error("channel client build failed , reason : ", e);
            throw e;
        }
    }

    @Override
    public BcosBlock.Block getBlockByNumber(BigInteger blockNumber) {
        return client.getBlockByNumber(blockNumber,false,false).getBlock();
    }

    @Override
    public BigInteger getBlockNumber() {
        return client.getBlockNumber().getBlockNumber();
    }

    @Override
    public String getCode(String address) {
        return client.getCode(address).getCode();
    }

    @Override
    public CryptoSuite getCryptoSuite() {
        return client.getCryptoSuite();
    }

    @Override
    public BcosTransaction getTransactionByHash(String transactionHash) {
        return client.getTransaction(transactionHash,true);
    }

    @Override
    public BcosTransactionReceipt getTransactionReceipt(String hash) {
        return client.getTransactionReceipt(hash,true);
    }
}
