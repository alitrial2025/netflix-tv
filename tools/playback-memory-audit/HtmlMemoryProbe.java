package audit;
import android.os.Debug;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
public class HtmlMemoryProbe {
 public static void main(String[] args) throws Exception {
  StringBuilder page=new StringBuilder(600000);
  String pad=new String(new char[900]).replace('\0','x');
  for(int i=0;i<512;i++) page.append("<a href=\"https://assets.example/").append(i).append("\">").append(pad).append("</a>");
  page.append("<a href=\"https://www.hotstar.com/1234567890/watch\">watch</a>");
  String html=page.toString();
  Class<?> cls=Class.forName(args[0]);
  Method method=cls.getDeclaredMethod(args[1],String.class);
  Object target=null;
  if(!Modifier.isStatic(method.getModifiers())) {
   for(var field:cls.getDeclaredFields()) {
    if(Modifier.isStatic(field.getModifiers()) && field.getType()==cls) {
     field.setAccessible(true);target=field.get(null);break;
    }
   }
   if(target==null) {
    var constructor=cls.getDeclaredConstructor();constructor.setAccessible(true);target=constructor.newInstance();
   }
  }
  System.gc(); Thread.sleep(100);
  long baseline=Debug.getNativeHeapAllocatedSize();
  AtomicLong peak=new AtomicLong(baseline);
  Thread sample=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){
   peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(),Math::max);Thread.sleep(10);
  }}catch(InterruptedException ignored){}});sample.setDaemon(true);sample.start();
  long started=System.nanoTime();
  Object result=method.invoke(target,html);
  peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(),Math::max);
  sample.interrupt();sample.join();
  System.out.println("HTML_MEMORY_PROBE bytes="+html.length()+" results="+result+" elapsedMs="+(System.nanoTime()-started)/1000000+" nativeBaselineKiB="+baseline/1024+" nativePeakKiB="+peak.get()/1024+" nativeGrowthKiB="+(peak.get()-baseline)/1024);
 }
}
