# Signed dependency jars break the fat jar

Bouncy Castle (via pac4j) ships signed jars. `src/main/assembly/jar-with-dependencies.xml` excludes
`META-INF/*.SF|RSA|DSA|EC`; otherwise the server jar fails signature verification.
