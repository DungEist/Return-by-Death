import java.lang.reflect.Method;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.mob.PiglinEntity;

public class CheckPiglin {
    public static void main(String[] args) throws Exception {
        System.out.println("PiglinBrain methods:");
        for (Method m : PiglinBrain.class.getDeclaredMethods()) {
            System.out.println(m.getName() + " " + m.getReturnType().getName());
        }
    }
}
