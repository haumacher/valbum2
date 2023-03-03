# VAlbum2 - Virtual Photo Album
The friendly home for all your digital memories

Mit VAlbum verwaltest Du Deine digitalen Photos und Videos ohne Dich in Abhängigkeit eines Cloud-Dienstleisters wie 
Google-Photos zu begeben. Trotzdem hast Du die Möglichkeit, auf Deine digitalen Erinnerungen über einen normalen 
Web-Browser zuzugreifen. Wenn Du von überall Zugriff auf Dein Photoalbum haben möchtest, kannst Du VAlbum ganz leicht 
auf einem RasberryPI Mini-Server installieren und über Deinen Internetanschluss freizugeben. Mit einer relativ 
geringen Einmalinvestition hast Du damit deinen eigenen riesigen Cloud-Speicher für Deine Photos.

## Wie funktioniert VAlbum?

VAlbum besteht aus zwei Komponenten, einem Java-Server und einer GWT-Anwendung, die in Deinem Browser läuft und die 
Photos vom VAlbum-Server abruft. Deine Photos organisierst Du wie bisher auch: Ein Ordner, der alle Deine Alben enthält. 
Jedes Album ist wiederum ein Ordner mit Photos und Videos. Du bist völlig frei bei der Gestaltung der Ordnerstruktur. 
VAlbum liest diesen Ordner und generiert hübsche Übersichtsseiten für Deine Alben. In VAlbum kannst Du Photos
beschriften, gruppieren und für die Präsentation auswählen. Dabei fasst VAlbum Deine Photos aber nie an und modifiziert 
oder löscht nie eine Deiner Dateien. Alle "Änderungen", die Du an einem Album vornimmst werden in separaten Dateien
neben Deinen Photos gespeichert.

## Wie baut man VAlbum?

Du benötigtst Git, eine Java 11 Runtime und Apache Maven. Nachdem Du das Repository geclont hast, baust Du mit dem 
folgenden Befehl im Projekt-Hauptverzeichnis:

```
mvn clean install
```

Eine Demo-Version kannst Du dann direkt starten und ausprobieren:

```
mvn exec:java@test-server -pl :image-server
```

Ist das geschafft, kannst Du die VAlbum-URL http://localhost:9090/valbum/ im Browser aufrufen.
