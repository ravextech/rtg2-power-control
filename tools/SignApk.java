import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

public final class SignApk {
    public static void main(String[] args)throws Exception {
        char[] password=new String(Files.readAllBytes(Paths.get(args[1])),"UTF-8").trim().toCharArray();
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(InputStream in=new FileInputStream(args[0])){store.load(in,password);}
        PrivateKey key=(PrivateKey)store.getKey(args[2],password);
        X509Certificate certificate=(X509Certificate)store.getCertificate(args[2]);
        if(key==null||certificate==null)throw new IllegalArgumentException("Signing alias not found");
        ApkSigner.SignerConfig config=new ApkSigner.SignerConfig.Builder("release",key,Collections.singletonList(certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(config)).setInputApk(new File(args[3])).setOutputApk(new File(args[4]))
            .setMinSdkVersion(23).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign();
        Arrays.fill(password,'\0');
        ApkVerifier.Result result=new ApkVerifier.Builder(new File(args[4])).setMinCheckedPlatformVersion(23).build().verify();
        if(!result.isVerified())throw new IllegalStateException("APK signature verification failed: "+result.getErrors());
        System.out.println("APK signature verified (v1="+result.isVerifiedUsingV1Scheme()+", v2="+result.isVerifiedUsingV2Scheme()+")");
    }
}
