

keytool -genkey -alias lifecosys-proxy-server -keysize 4096 -validity 36500 -keyalg RSA -dname CN=littleproxy -keypass killccp-server -storepass killccp-server -keystore lifecosys-proxy-server-keystore.jks
keytool -exportcert -alias lifecosys-proxy-server -keystore lifecosys-proxy-server-keystore.jks -storepass killccp-server -file lifecosys-proxy-server-keystore.cert
keytool -keystore lifecosys-proxy-server-for-client-trust-keystore.jks -import -alias lifecosys-proxy-serve -file  lifecosys-proxy-server-keystore.cert



keytool -genkey -alias lifecosys-proxy-client -keysize 4096 -validity 36500 -keyalg RSA -dname CN=littleproxy -keypass killccp-server -storepass killccp-server -keystore lifecosys-proxy-client-keystore.jks
keytool -exportcert -alias lifecosys-proxy-client -keystore lifecosys-proxy-client-keystore.jks -storepass killccp-server -file lifecosys-proxy-client-keystore.cert
keytool -keystore lifecosys-proxy-client-for-server-trust-keystore.jks -import -alias lifecosys-proxy-client -file  lifecosys-proxy-client-keystore.cert

            keytool -genkey -alias cn-textile-email-server -keyalg RSA -keystore cn-textile-email-server-keystore
                To export the self-signed public key to cert
                  keytool -export -rfc -keystore cn-textile-email-server-keystore -storepass cn@textile-james$email.com#server -alias cn-textile-email-server -file  cn-textile-email-server-keystore.cert
                  keytool -keystore $JAVA_HOME/jre/lib/security/jssecacerts -import -alias cn-textile-email-server -file  cn-textile-email-server-keystore.cert