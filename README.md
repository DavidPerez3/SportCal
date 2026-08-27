# SportCal Android MVP

App Android para compartir como APK entre amigos.

## Qué incluye
- Calendario mensual optimizado para móvil.
- Filtros: **LaLiga, Champions, Europa League, selecciones, Euroliga y Grand Slam**.
- Tocar un día para ver su agenda.
- **Compartir PNG**: genera una imagen del calendario, la guarda en `Pictures/SportCal` y abre el menú de compartir de Android.
- **Imprimir / PDF**: abre el sistema de impresión de Android, donde se puede elegir **Guardar como PDF**.
- Sin login, sin servidor y usable offline.

> Los datos incluidos son un conjunto inicial para probar el MVP. Para uso real durante toda la temporada hay que conectar una fuente de datos/backend que actualice partidos, sorteos y cambios de horario.

## Sacar el APK con GitHub Actions
El repositorio incluye `.github/workflows/build-apk.yml`.

1. Cada `push` a `main` lanza automáticamente **Build Android APK**.
2. También se puede ejecutar manualmente desde la pestaña Actions.
3. Al terminar, descarga el artefacto **SportCal-debug-apk**.
4. Dentro estará `app-debug.apk`, listo para instalar y pasar a otra persona.

## Android Studio
1. Abre este repositorio como proyecto en Android Studio.
2. Deja que sincronice Gradle/SDK.
3. Ve a **Build > Build APK(s)**.
4. El APK estará en `app/build/outputs/apk/debug/app-debug.apk`.

## Requisitos del móvil
- Android 10 (API 29) o posterior.
- Para instalar el APK directamente, Android puede pedir permiso para instalar apps de origen desconocido desde la app con la que abras el archivo.

## Estructura
- `app/src/main/assets/index.html`: interfaz, calendario, filtros y eventos.
- `app/src/main/java/com/variado/sportcal/MainActivity.java`: envoltorio Android + exportación PNG/PDF.
- `.github/workflows/build-apk.yml`: compilación automática del APK.
