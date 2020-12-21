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
package com.webank.blockchain.data.export.service;

import cn.hutool.core.collection.CollectionUtil;
import com.webank.blockchain.data.export.common.bo.data.BlockInfoBO;
import com.webank.blockchain.data.export.db.service.DataStoreService;
import com.webank.blockchain.data.export.task.DataExportExecutor;

import java.util.List;

/**
 * BlockStoreService
 *
 * @Description: BlockStoreService
 * @author maojiayu
 * @data Jul 5, 2019 4:09:23 PM
 *
 */
public class BlockStoreService {

    public static void store(BlockInfoBO blockInfo) {
        List<DataStoreService> dataStoreServiceList = DataExportExecutor.crawler.get().getDataStoreServiceList();
        if (CollectionUtil.isEmpty(dataStoreServiceList)) {
            return;
        }
        for (DataStoreService dataStoreService : dataStoreServiceList) {
            dataStoreService.storeBlockInfoBO(blockInfo);
        }
    }


}
