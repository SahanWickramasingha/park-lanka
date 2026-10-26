package lk.sahan.parking.desktop;

import java.io.*;
import java.util.Properties;

/** Captured once per JVM: rebuilding files on disk does not update a running desktop. */
final class DesktopBuildInfo {
    private static final Properties BUILD=load();
    static String label(){return "Park Lanka | "+value("version")+" | build "+value("builtAt");}
    static String title(String role){return "Park Lanka | "+(role.isBlank()?"Login":role);}
    static String source(){return DesktopBuildInfo.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();}
    private static String value(String key){String value=BUILD.getProperty(key,"development");return value.contains("${")?"development":value;}
    private static Properties load(){
        Properties result=new Properties();
        try(InputStream stream=DesktopBuildInfo.class.getResourceAsStream("/desktop-build.properties")){if(stream!=null)result.load(stream);}
        catch(IOException ignored){/* IDE launches can still run without generated build metadata. */}
        return result;
    }
    private DesktopBuildInfo(){}
}
