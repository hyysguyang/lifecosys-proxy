
//For testing.....

keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-server-keystore.jks
keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp -file lifecosys-proxy-server-keystore.cert
keytool -keystore lifecosys-proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp -storepass killccp


keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-client-keystore.jks
keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp -file lifecosys-proxy-client-keystore.cert
keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert -keypass killccp -storepass killccp


//Full command
keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-server-keystore.jks && keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp -file lifecosys-proxy-server-keystore.cert && keytool -keystore lifecosys-proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp -storepass killccp && keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp -storepass killccp -keystore lifecosys-proxy-client-keystore.jks && keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp -file lifecosys-proxy-client-keystore.cert && keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert -keypass killccp -storepass killccp



///For production

keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-server -storepass killccp-server -keystore lifecosys-proxy-server-keystore.jks
keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp-server -file lifecosys-proxy-server-keystore.cert
keytool -keystore lifecosys-proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp-client -storepass killccp-client


keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-client -storepass killccp-client -keystore lifecosys-proxy-client-keystore.jks
keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp-client -file lifecosys-proxy-client-keystore.cert
keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert -keypass killccp-server -storepass killccp-server


//Full command

keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-server -storepass killccp-server -keystore lifecosys-proxy-server-keystore.jks && keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp-server -file lifecosys-proxy-server-keystore.cert && keytool -keystore lifecosys-proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert  -keypass killccp-client -storepass killccp-client && keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 3650 -keyalg RSA -dname "cn=proxy, ou=Toolkit, o=Lifecosys, c=US" -keypass killccp-client -storepass killccp-client -keystore lifecosys-proxy-client-keystore.jks && keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp-client -file lifecosys-proxy-client-keystore.cert && keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert -keypass killccp-server -storepass killccp-server




          