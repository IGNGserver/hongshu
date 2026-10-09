@echo off
set ROOT=%~dp0
if defined JAVA_HOME (set JAVA=%JAVA_HOME%\bin\java.exe) else (set JAVA=java.exe)
"%JAVA%" -classpath "%ROOT%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
