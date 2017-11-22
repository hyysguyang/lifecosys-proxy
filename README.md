# How to build

`./gradlew clean -x test build`

# How to use

Generate your ssl keystore with your password(Just replace the $password):
`keytool -genkey -alias lifecosys-proxy -keysize 4096 -validity 36500 -keyalg RSA -dname CN=com.lifecosys.dev -keypass $password -storepass $password -keystore lifecosys-keystore.jks`
`keytool -exportcert -alias lifecosys-proxy -keystore lifecosys-keystore.jks -storepass $password -file lifecosys-proxy-cert`

Then copy the generated lifecosys-keystore.jks and lifecosys-proxy-cert to the config directory: both
local proxy server and the remote proxy server.