#/bin/bash

export STORE_PASS=$1
export KEY_PASS=$2

keytool -v -genkeypair -alias x9-matera -dname "CN=x9qrcode.matera.com, O=Matera Systems, OU=X9, C=BR, ST=SP, L=Campinas" \
-validity 730 -keyalg RSA -keysize 2048 -keystore x9-matera.jks -storepass ${STORE_PASS} -keypass ${KEY_PASS} \
-sigalg SHA256withRSA -storetype PKCS12 \
-ext ku:c=dig,keyEnc \
-ext eku=sa,ca \
-ext "san=dns:*.matera.com"

keytool -v -list -keystore x9-matera.jks -storepass ${STORE_PASS}

keytool -certreq -alias x9-matera -keystore x9-matera.jks -storepass ${STORE_PASS} -keypass ${KEY_PASS} > x9-matera.csr