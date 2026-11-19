import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.jetbrains.zip.signer.metadata.SignatureAlgorithm;
import org.jetbrains.zip.signer.signer.SignerInfoLoader;
import org.jetbrains.zip.signer.signing.DefaultSignatureProvider;
import org.jetbrains.zip.signer.signing.ZipSigner;
import org.jetbrains.zip.signer.verifier.MissingSignatureResult;
import org.jetbrains.zip.signer.verifier.SuccessfulVerificationResult;
import org.jetbrains.zip.signer.verifier.ZipVerifier;

/** 使用 JetBrains 的 ZIP 签名库，避免在 Java 进程参数或 Gradle 日志中携带口令。 */
class ReleaseArchiveSigner {
    public static void main(String[] args) {
        try {
            sign(args);
        } catch (Exception error) {
            // 不打印第三方异常原文；PEM 解析失败时，其消息不一定适合写入构建日志。
            System.err.println(error instanceof ReleaseException
                    ? error.getMessage()
                    : "签名失败：请检查文件权限、加密 PEM、私钥口令与证书是否对应。");
            System.exit(1);
        }
    }

    private static void sign(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        if (!(ZipVerifier.verify(input) instanceof MissingSignatureResult)) {
            throw new ReleaseException("候选包需要使用未签名的原始 ZIP。");
        }

        boolean local = args.length == 4;
        String certificate = local ? Files.readString(Path.of(args[2])) : secret("LEXIMEET_SIGNING_CERTIFICATE");
        String key = local ? Files.readString(Path.of(args[3])) : secret("LEXIMEET_SIGNING_PRIVATE_KEY");
        if (!key.stripLeading().startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----")) {
            throw new ReleaseException("私钥必须是加密的 PKCS#8 PEM（脚本生成的 private.pem）。");
        }
        char[] password = local ? readPassword() : secret("LEXIMEET_SIGNING_PASSWORD").toCharArray();
        try {
            var signer = SignerInfoLoader.INSTANCE.loadSignerInfoFromText(key, certificate, password);
            var leaf = signer.getCertificates().getFirst();
            leaf.checkValidity();
            var provider = new DefaultSignatureProvider(
                    SignatureAlgorithm.RSA_PKCS1_V1_5_WITH_SHA256, signer.getPrivateKey());
            // 先验证签名和去除签名后的字节，再把结果放到最终路径。出现错误不留下可误传的包。
            Path signed = Files.createTempFile(output.getParent(), ".leximeet-signed-", ".zip");
            try {
                ZipSigner.sign(input.toFile(), signed.toFile(), signer.getCertificates(), provider);
                if (!(ZipVerifier.verify(signed) instanceof SuccessfulVerificationResult verified)
                        || !verified.isSignedBy(leaf)) {
                    throw new ReleaseException("签名校验失败：请确认私钥与证书相匹配。");
                }
                verifyPayload(input, signed);
                Files.move(signed, output);
            } finally {
                Files.deleteIfExists(signed);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void verifyPayload(Path input, Path signed) throws Exception {
        Path unsigned = Files.createTempFile(signed.getParent(), ".leximeet-payload-", ".zip");
        try {
            ZipSigner.unsign(signed.toFile(), unsigned.toFile());
            if (Files.mismatch(input, unsigned) != -1) {
                throw new ReleaseException("签名包的内容与通过测试的原始 ZIP 不一致。");
            }
        } finally {
            Files.deleteIfExists(unsigned);
        }
    }

    private static String secret(String name) throws ReleaseException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new ReleaseException("缺少发布环境变量：" + name);
        }
        return value;
    }

    private static char[] readPassword() throws ReleaseException {
        Console console = System.console();
        if (console == null) {
            throw new ReleaseException("请在本机终端运行签名脚本，以便无回显输入口令。");
        }
        char[] password = console.readPassword("签名私钥口令：");
        if (password == null || password.length == 0) {
            throw new ReleaseException("签名私钥口令为空。");
        }
        return password;
    }

    private static class ReleaseException extends Exception {
        ReleaseException(String message) {
            super(message);
        }
    }
}
