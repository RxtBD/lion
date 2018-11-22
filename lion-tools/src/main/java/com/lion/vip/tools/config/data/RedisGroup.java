/**
 * FileName: RedisGroup
 * Author:   Ren Xiaotian
 * Date:     2018/11/22 16:15
 */

package com.lion.vip.tools.config.data;

import java.util.Collections;
import java.util.List;

/**
 * Redis组
 */
public class RedisGroup {
    public List<RedisNode> redisNodeList = Collections.emptyList();

    public RedisGroup() {
    }

    public RedisGroup(List<RedisNode> redisNodeList) {
        this.redisNodeList = redisNodeList;
    }

    @Override
    public String toString() {
        return "RedisGroup{" +
                "redisNodeList=" + redisNodeList +
                '}';
    }
}
