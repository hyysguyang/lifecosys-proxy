#!/bin/sh

rm -rf temp
mkdir -p temp/base
mkdir -p temp/dex-jar
cd temp/base
jar -xf /Develop/DevelopTools/scala-2.9.2/lib/scala-library.jar

jar cvfm ../scala-library-collection-immutable.jar  META-INF/MANIFEST.MF   scala/collection/immutable library.properties
jar cvfm ../scala-library-collection-mutable.jar  META-INF/MANIFEST.MF   scala/collection/mutable library.properties
jar cvfm ../scala-library-collection-parallel.jar  META-INF/MANIFEST.MF   scala/collection/parallel library.properties

rm -rf scala/collection/mutable scala/collection/immutable scala/collection/parallel

jar cvfm ../scala-library-collection-base.jar  META-INF/MANIFEST.MF   scala/collection library.properties
jar cvfm ../scala-library-util-reflect.jar  META-INF/MANIFEST.MF   scala/util scala/reflect library.properties

rm -rf scala/collection scala/util scala/reflect

jar cvfm ../scala-library-base.jar  META-INF/MANIFEST.MF   scala library.properties


cd ..

dx --dex --output=dex-jar/scala-library-collection-immutable-dex.jar  scala-library-collection-immutable.jar
dx --dex --output=dex-jar/scala-library-collection-mutable-dex.jar  scala-library-collection-mutable.jar
dx --dex --output=dex-jar/scala-library-collection-parallel-dex.jar  scala-library-collection-parallel.jar
dx --dex --output=dex-jar/scala-library-collection-base-dex.jar  scala-library-collection-base.jar
dx --dex --output=dex-jar/scala-library-util-reflect-dex.jar  scala-library-util-reflect.jar
dx --dex --output=dex-jar/scala-library-base-dex.jar  scala-library-base.jar


#for i in configs/framework/*.jar; do adb push $i /data/framework/; done


#/data/framework/scala-library-collection-immutable-dex.jar:/data/framework/scala-library-collection-mutable-dex.jar:/data/framework/scala-library-collection-parallel-dex.jar:/data/framework/scala-library-collection-base-dex.jar:/data/framework/scala-library-util-reflect-dex.ja:/data/framework/scala-library-base-dex.jar