# embedd-webapp-maven-plugin

Maven-Plugin, das es erlaubt "stand-alone" Java-Anwendungen zu erzeugen, die Web-Inhalt bereitstellen.

Normalerweise werden Web-Anwendungen in eine `war`-Datei gepackt und müssen in einen Web-Container installiert werden. 
Dies erfordert die separate Installation eines solchen Containers. Da das mit nicht vernachlässigbarem administrativen
Aufwand verbunden ist, ist das nur für "große" Anwendungsszenarien sinnvoll.

Alternativ kann eine Java-Anwendung einen Web-Container enthalten. 