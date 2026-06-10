@REM Maven Wrapper for Windows
@SETLOCAL
@SET MAVEN_PROJECTBASEDIR=%~dp0

@IF NOT DEFINED JAVA_HOME (
    @FOR %%e in (java.exe) do @SET JAVA_HOME=%%~dp$PATH:e..
)

@IF NOT EXIST "%JAVA_HOME%\bin\java.exe" (
    @ECHO JAVA_HOME is not set and 'java' is not on PATH.
    @EXIT /b 1
)

@"%JAVA_HOME%\bin\java.exe" ^
    -classpath "%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar" ^
    -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" ^
    org.apache.maven.wrapper.MavenWrapperMain ^
    %*
