# Preserve the version-checked default-network boundary on API 23 consumers.
-keep,allowshrinking,allowobfuscation class com.sandrox.osservio.runtime.Api24NetworkPathRegistration { *; }
-keep,allowshrinking,allowobfuscation class com.sandrox.osservio.runtime.Api24NetworkPathRegistration$* { *; }
