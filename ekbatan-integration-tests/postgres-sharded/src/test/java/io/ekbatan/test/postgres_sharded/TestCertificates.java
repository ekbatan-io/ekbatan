package io.ekbatan.test.postgres_sharded;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Throwaway TLS material for a client-certificate login, made fresh for each test run so no key
 * is ever committed: a certificate authority, a server certificate for {@code localhost}, and a
 * client certificate for one database user whose private key is encrypted with a password.
 *
 * <p>The client key is written the way PostgreSQL's JDBC driver reads an encrypted key: PKCS-8,
 * DER, encrypted with a password-based cipher the JDK itself can undo - so the only way to open it
 * is the key password, which the tests hand to the driver as its {@code sslpassword} setting.
 */
final class TestCertificates {

    /** Where each file was written. */
    record Material(
            Path caCertificate, Path serverCertificate, Path serverKey, Path clientCertificate, Path clientKey) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestCertificates() {}

    /**
     * Writes the whole set into {@code directory}.
     *
     * @param directory where to write.
     * @param clientUser the database user the client certificate names (its common name).
     * @param clientKeyPassword the password the client key is encrypted with.
     * @return the written files.
     */
    static Material write(Path directory, String clientUser, char[] clientKeyPassword) throws Exception {
        var ca = keyPair();
        var caCertificate = certificate("CN=ekbatan-test-ca", ca, "CN=ekbatan-test-ca", ca.getPrivate(), true, null);

        var server = keyPair();
        var serverCertificate = certificate(
                "CN=localhost",
                server,
                "CN=ekbatan-test-ca",
                ca.getPrivate(),
                false,
                new GeneralNames(new GeneralName[] {
                    new GeneralName(GeneralName.dNSName, "localhost"),
                    new GeneralName(GeneralName.iPAddress, "127.0.0.1")
                }));

        var client = keyPair();
        var clientCertificate =
                certificate("CN=" + clientUser, client, "CN=ekbatan-test-ca", ca.getPrivate(), false, null);

        var files = new Material(
                directory.resolve("ca.crt"),
                directory.resolve("server.crt"),
                directory.resolve("server.key"),
                directory.resolve("client.crt"),
                directory.resolve("client.pk8"));
        pem(files.caCertificate(), "CERTIFICATE", caCertificate.getEncoded());
        pem(files.serverCertificate(), "CERTIFICATE", serverCertificate.getEncoded());
        pem(files.serverKey(), "PRIVATE KEY", server.getPrivate().getEncoded());
        pem(files.clientCertificate(), "CERTIFICATE", clientCertificate.getEncoded());
        Files.write(files.clientKey(), encrypted(client.getPrivate(), clientKeyPassword));
        return files;
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(
            String subject,
            KeyPair subjectKeys,
            String issuer,
            PrivateKey issuerKey,
            boolean authority,
            GeneralNames alternativeNames)
            throws Exception {
        var now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuer),
                new BigInteger(64, RANDOM),
                Date.from(now.minus(Duration.ofHours(1))),
                Date.from(now.plus(Duration.ofDays(1))),
                new X500Name(subject),
                subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(authority));
        if (authority) {
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        } else {
            builder.addExtension(
                    Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(new KeyPurposeId[] {
                KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth
            }));
        }
        if (alternativeNames != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, alternativeNames);
        }
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    /** PKCS-8 DER, encrypted - the shape PostgreSQL's JDBC driver opens with {@code sslpassword}. */
    private static byte[] encrypted(PrivateKey key, char[] password) throws GeneralSecurityException, IOException {
        var algorithm = "PBEWithSHA1AndDESede";
        var salt = new byte[8];
        RANDOM.nextBytes(salt);
        var parameters = new PBEParameterSpec(salt, 2048);
        var secret = SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password));
        var cipher = Cipher.getInstance(algorithm);
        cipher.init(Cipher.ENCRYPT_MODE, secret, parameters);
        var algorithmParameters = AlgorithmParameters.getInstance(algorithm);
        algorithmParameters.init(parameters);
        return new EncryptedPrivateKeyInfo(algorithmParameters, cipher.doFinal(key.getEncoded())).getEncoded();
    }

    private static void pem(Path file, String type, byte[] der) throws IOException {
        var body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }
}
