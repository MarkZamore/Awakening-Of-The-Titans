import com.sun.tools.attach.VirtualMachine;
public class AttachQa {public static void main(String[] a)throws Exception{var vm=VirtualMachine.attach(a[0]);try{vm.loadAgent(a[1],a.length>2?a[2]:"");}finally{vm.detach();}}}
