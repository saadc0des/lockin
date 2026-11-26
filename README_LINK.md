# Testing the link code on a computer (optional)
`LinkProto.kt` and `LinkCore.kt` are plain JVM Kotlin. `jvmtest/LinkCoreTest.kt` drives them against the real PC app:
    kotlinc -jvm-target 17 -d out/core app/src/main/java/com/saad/lockin/LinkProto.kt app/src/main/java/com/saad/lockin/LinkCore.kt
    kotlinc -jvm-target 17 -cp out/core -d out/test jvmtest/LinkCoreTest.kt
    LOCKIN_ANDROID_CP=out/core:out/test:<kotlin-stdlib.jar> python3 -m unittest tests.test_link_android   (run in the PC project)
