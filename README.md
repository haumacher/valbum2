# VAlbum2 - Virtual Photo Album
The friendly home for all your digital memories

Mit [VAlbum](https://github.com/haumacher/valbum2) verwaltest Du Deine digitalen Photos und Videos ohne Dich in Abhängigkeit eines Cloud-Dienstleisters wie 
Google-Photos zu begeben. Trotzdem hast Du die Möglichkeit, auf Deine digitalen Erinnerungen über einen normalen 
Web-Browser zuzugreifen. Wenn Du von überall Zugriff auf Dein Photoalbum haben möchtest, kannst Du VAlbum ganz leicht 
auf einem [RasberryPI](https://www.raspberrypi.org/) Mini-Server installieren und über Deinen Internetanschluss freizugeben. Mit einer relativ 
geringen Einmalinvestition hast Du damit deinen eigenen riesigen Cloud-Speicher für Deine Photos.

## Wie funktioniert VAlbum?

VAlbum besteht aus zwei Komponenten, einem [Java](https://adoptium.net/de/temurin/releases/?version=11)-Server und 
einer [GWT](https://www.gwtproject.org/)-Anwendung, die in Deinem Browser läuft und die 
Photos vom VAlbum-Server abruft. Deine Photos organisierst Du wie bisher auch: Ein Ordner, der alle Deine Alben enthält. 
Jedes Album ist wiederum ein Ordner mit Photos und Videos. Du bist völlig frei bei der Gestaltung der Ordnerstruktur. 
VAlbum liest diesen Ordner und generiert hübsche Übersichtsseiten für Deine Alben. In VAlbum kannst Du Photos
beschriften, gruppieren und für die Präsentation auswählen. Dabei fasst VAlbum Deine Photos aber nie an und modifiziert 
oder löscht nie eine Deiner Dateien. Alle "Änderungen", die Du an einem Album vornimmst werden in separaten Dateien
neben Deinen Photos gespeichert.

## Wie baut man VAlbum?

Du benötigtst Git, eine [Java 11 Runtime](https://adoptium.net/de/temurin/releases/?version=11) 
und [Apache Maven](https://maven.apache.org/). Nachdem Du das Repository geclont hast, baust Du mit dem 
folgenden Befehl im Projekt-Hauptverzeichnis:

```
mvn clean install
```

Eine Demo-Version mit einem Test-Album kannst Du dann direkt starten und ausprobieren:

```
mvn exec:java@test-server -pl :image-server
```

Ist das geschafft, kannst Du die VAlbum-URL http://localhost:9090/valbum/ im Browser aufrufen. Wenn das funktioniert
hat, erhälst du ungefähr so eine Ansicht:

![image](https://user-images.githubusercontent.com/5607145/222809565-79870212-b774-4752-b499-92b89a3914cb.png)

## Wie startet man VAlbum?

Wenn Du VAlbum gebaut hast (siehe oben), dann entsteht in dem Ordner `image-server/target` eine Datei mit Namen `image-server-jar-with-dependencies.jar`. In dieser Datei ist der gesamte ausführbare Code von VAlbum enthalten. Du kannst diese Datei auf Deinen Album-Server kopieren, oder einfach lokal auf Deinem Rechner starten:

```
java -jar image-server-jar-with-dependencies.jar --basepath C:\path\to\your\photos
```

Den Port, auf dem der Server läuft kann man z.B. mit der zusätzlichen Option `--port 8080` ändern. Der Erste Teil in der URL kann z.B. über `--contextpath photos` geändert werden.
