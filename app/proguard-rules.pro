


-keep class org.conscrypt.Conscrypt { *; }
-keep class org.conscrypt.OpenSSLProvider { *; }


-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Shrink and optimise, but keep every name. Measured: renaming saves 0.3 MB on
# a 6.7 MB APK. Against that, a crash line or an exception in the log - and
# this app logs exception class names on purpose, in the self-test and the
# daemon supervisor - would read "a.b: ..." to the person reporting it, and
# to whoever reads the report, unless a mapping file is published with every
# release and applied by hand. Not worth 0.3 MB.
-dontobfuscate
