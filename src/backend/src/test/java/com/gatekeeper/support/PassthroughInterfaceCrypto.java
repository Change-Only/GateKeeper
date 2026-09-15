package com.gatekeeper.support;

import com.gatekeeper.crypto.InterfaceCryptoService;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;

/**
 * 测试替身：**「保护开关关闭」语义**的 {@link InterfaceCryptoService}（T17）。
 *
 * <p>存量单测关心的是各自业务逻辑（分组筛选、批量替换、文档计数…），
 * 不该被迫关心字段级加解密。本替身把行为定义成「开关关闭」的样子：</p>
 * <ul>
 *   <li>不解密、不加密、不掩码（值原样进原样出）；</li>
 *   <li>{@code isMask} 恒 false ⇒ 不会触发"掩码回写防线"；</li>
 *   <li>{@code blindIndex} 返回 null ⇒ 等价于"库里没有盲索引列的行"。</li>
 * </ul>
 *
 * <p>⚠ 需要验证<b>加密本身</b>的用例请用真的 {@code InterfaceCryptoServiceImpl}
 * （见 {@code InterfaceCryptoServiceTest}），不要用本替身 ——
 * 它证明不了任何加密行为。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
public class PassthroughInterfaceCrypto implements InterfaceCryptoService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String encryptField(String plain) {
        return plain;
    }

    @Override
    public String decryptField(String stored) {
        return stored;
    }

    @Override
    public boolean isEncrypted(String value) {
        return false;
    }

    @Override
    public boolean isMask(String value) {
        return false;
    }

    @Override
    public String blindIndex(String plain) {
        return null;
    }

    @Override
    public void applyToInterface(ApiInterface iface) {
        // 开关关闭：路径原样明文落库，盲索引列留空
        if (iface != null) {
            iface.setInterfacePathHash(null);
        }
    }

    @Override
    public void decryptInPlace(ApiInterface iface) {
        // no-op：本来就是明文
    }

    @Override
    public void applyToParam(ApiParam param) {
        // no-op：开关关闭时明文落库
    }

    @Override
    public void decryptInPlace(ApiParam param) {
        // no-op：本来就是明文
    }

    @Override
    public void invalidate() {
        // no-op
    }
}
