import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.MarkerManager;
public class ConnectionLogProbe {
 public static void main(String[] args) throws Exception {
  Class<?> connection = Class.forName("net.minecraft.class_2535");
  java.lang.reflect.Field field = connection.getDeclaredField("field_11642");
  field.setAccessible(true);
  Object nativeLogger = field.get(null);
  String actualName = (String) Class.forName("org.slf4j.Logger").getMethod("getName").invoke(nativeLogger);
  if (!"net.minecraft.class_2535".equals(actualName)) throw new IllegalStateException("Native logger changed: " + actualName);
  System.out.println("NATIVE_CONNECTION_LOGGER=" + actualName);
  Logger log = LogManager.getLogger("net.minecraft.class_2535");
  for (String text : new String[]{"Failed to sent packet", "Double fault", "Skipping packet due to errors", "Timeout"})
   log.debug(text, new ArrayIndexOutOfBoundsException("synthetic diagnostic " + text));
  log.debug(MarkerManager.getMarker("NETWORK_PACKETS"), "UNWANTED_PACKET_TRACE synthetic");
  log.debug("UNWANTED_OTHER_DEBUG", new IllegalStateException("synthetic unrelated"));
  log.info("EXPECTED_INFO synthetic");
  log.warn("EXPECTED_WARN synthetic");
  log.error("EXPECTED_ERROR synthetic", new IllegalStateException("synthetic retained error"));
  LogManager.shutdown();
 }
}
