package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.AppCredential;
import org.apache.ibatis.annotations.Mapper;

/**
 * 应用凭证表 Mapper — 负责 app_credential 表的数据访问
 *
 * <p>T03a 多套凭证 + 灰度轮换：取代存量 app.app_key/app_secret 的「一应用一密钥」模型。
 * rotateFlag=0 表示主密钥，=1 表示轮换中（与原主密钥共存 7 天）。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Mapper
public interface AppCredentialMapper extends BaseMapper<AppCredential> {
}
