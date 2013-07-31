
//For testing.....

keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-server-keystore.jks
keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp -file lifecosys-proxy-server-keystore.cert
keytool -keystore lifecosys-proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp -storepass killccp


keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-client-keystore.jks
keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp -file lifecosys-proxy-client-keystore.cert
keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert -keypass killccp -storepass killccp

//For Android certification
//Download bcprov-ext-jdk16-1.45.jar
// aria2c http://repo2.maven.org/maven2/org/bouncycastle/bcprov-ext-jdk16/1.45/bcprov-ext-jdk16-1.45.jar
keytool -import -v -trustcacerts -file lifecosys-proxy-client-keystore.cert -alias lifecosys-proxy-client -keystore proxy_android_trust_keystore.bks -keypass killccp -storepass killccp -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "bcprov-ext-jdk16-1.45.jar" -storetype BKS


//Full command
keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-server-keystore.jks && keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp -file lifecosys-proxy-server-keystore.cert && keytool -keystore lifecosys-proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp -storepass killccp && keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-client-keystore.jks && keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp -file lifecosys-proxy-client-keystore.cert && keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert -keypass killccp -storepass killccp



///For production

keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-server -storepass killccp-server -keystore lifecosys-proxy-server-keystore.jks
keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp-server -file lifecosys-proxy-server-keystore.cert
keytool -keystore proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp-client -storepass killccp-client
keytool -keystore proxy_server_android_trust_keystore.bks -import -alias lifecosys-proxy-client -file lifecosys-proxy-client-keystore.cert  -keypass killccp-server -storepass killccp-server -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "bcprov-ext-jdk16-1.45.jar" -storetype BKS


keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-client -storepass killccp-client -keystore lifecosys-proxy-client-keystore.jks
keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp-client -file lifecosys-proxy-client-keystore.cert
keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert -keypass killccp-server -storepass killccp-server

//For Android
#keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-client -storepass killccp-client -keystore proxy_android_keystore.bks -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "bcprov-ext-jdk16-1.45.jar" -storetype BKS
#keytool -exportcert -alias lifecosys-proxy-client -keystore proxy_android_keystore.bks -storepass killccp-client -file proxy_android_keystore.cert
#keytool -keystore proxy_android_trust_keystore.bks -import -alias lifecosys-proxy-client -file lifecosys-proxy-client-keystore.cert  -keypass killccp-server -storepass killccp-server -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "bcprov-ext-jdk16-1.45.jar" -storetype BKS


//For Android certification
//Download bcprov-ext-jdk16-1.45.jar
// aria2c http://repo2.maven.org/maven2/org/bouncycastle/bcprov-ext-jdk16/1.45/bcprov-ext-jdk16-1.45.jar
#keytool -keystore proxy_android_trust_keystore.bks -import -alias lifecosys-proxy-client -file lifecosys-proxy-client-keystore.cert  -keypass killccp-server -storepass killccp-server -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "bcprov-ext-jdk16-1.45.jar" -storetype BKS


//Full command

keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-server -storepass killccp-server -keystore lifecosys-proxy-server-keystore.jks && keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp-server -file lifecosys-proxy-server-keystore.cert && keytool -keystore proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp-client -storepass killccp-client && keytool -keystore proxy_server_android_trust_keystore.bks -import -alias lifecosys-proxy-client -file lifecosys-proxy-server-keystore.cert  -keypass killccp-server -storepass killccp-server -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "bcprov-ext-jdk16-1.45.jar" -storetype BKS





Prerequisites:

1. Install Unlimited JCE Policy for oracle jdk.
          
