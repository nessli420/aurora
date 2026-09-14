import com.aurora.music.playback.*;
import com.aurora.music.playback.engine.*;
import java.lang.reflect.*;
import java.util.*;
public class EffectsAnalysis {
  static Object test;
  static Method params;
  static DspParams rich() throws Exception { return (DspParams)params.invoke(test); }
  static PrecisionDspCoefficients promoted(DspParams p) {
    Coeffs c=DspCoeffBuilder.INSTANCE.build(p,48000);
    BiquadCoefficients[] b=new BiquadCoefficients[c.getNBiquads()];
    for(int i=0;i<b.length;i++) b[i]=new BiquadCoefficients(c.getB0()[i],c.getB1()[i],c.getB2()[i],c.getA1()[i],c.getA2()[i]);
    return new PrecisionDspCoefficients(48000,b,c.getPreampLin(),c.getBalL(),c.getBalR(),c.getWidth(),c.getSatDrive(),c.getDelayL(),c.getDelayR(),c.getTrimL(),c.getTrimR(),c.getCrossfeedAmt(),c.getCrossfeedLpfA(),c.getCrossfeedDelay(),c.getLimiterEnabled(),c.getCeilingLin(),c.getLimAtt(),c.getLimRel(),c.getCompEnabled(),c.getCompThreshLin(),c.getCompRatio(),c.getCompAtt(),c.getCompRel());
  }
  static double[] run(double[] samples,PrecisionDspCoefficients c) {
    AudioStreamFormat f=new AudioStreamFormat(48000,ChannelLayout.STEREO);
    PrecisionEffectsKernel k=new PrecisionEffectsKernel(f); AudioBlock b=new AudioBlock(f,256); double[] out=new double[samples.length];
    for(int offset=0;offset<samples.length;offset+=512) { int n=Math.min(512,samples.length-offset);System.arraycopy(samples,offset,b.getSamples(),0,n);b.begin(n/2,Long.MIN_VALUE,Long.MIN_VALUE);k.process(b,c);System.arraycopy(b.getSamples(),0,out,offset,n); }
    return out;
  }
  static void metric(String label,double[] a,double[] b) {
    double max=0,sum=0;int at=0;
    for(int i=0;i<a.length;i++){double e=Math.abs(a[i]-b[i]);if(e>max){max=e;at=i;}sum+=e*e;}
    double rms=Math.sqrt(sum/a.length);
    System.out.printf(Locale.ROOT,"%s peak=%.12g rms=%.12g peakDbFS=%.3f rmsDbFS=%.3f frame=%d%n",label,max,rms,20*Math.log10(max),20*Math.log10(rms),at/2);
  }
  static double[] transposed(double[] input,PrecisionDspCoefficients c) {
    double[] out=input.clone(),z1=new double[c.getNBiquads()*2],z2=new double[c.getNBiquads()*2];
    for(int i=0;i<out.length;i++){double s=out[i];for(int band=0;band<c.getNBiquads();band++){
      BiquadCoefficients b=c.filter(band);int state=band*2+i%2;double y=b.getB0()*s+z1[state];
      z1[state]=b.getB1()*s-b.getA1()*y+z2[state];z2[state]=b.getB2()*s-b.getA2()*y;s=y;
    }out[i]=s;}return out;
  }
  static DspParams variant(DspParams p,String kind) {
    boolean eq=kind.equals("eq")||kind.equals("graphic");
    float[] freqs=eq?p.getGraphicFreqs():new float[]{24000};
    java.util.List<DspBand> bands=kind.equals("eq")?p.getParametric():java.util.Collections.emptyList();
    return new DspParams(p.getGraphic(),freqs,p.getGraphicQ(),bands,eq?0:p.getPreampDb(),eq?0:p.getBalance(),eq?1:p.getWidth(),eq?0:p.getCrossfeed(),eq?0:p.getSaturation(),eq?0:p.getDelayLeftMs(),eq?0:p.getDelayRightMs(),eq?0:p.getTrimLeftDb(),eq?0:p.getTrimRightDb(),!eq&&p.getLimiterEnabled(),p.getLimiterCeilingDb(),!eq&&p.getCompEnabled(),p.getCompThreshDb(),p.getCompRatio());
  }
  public static void main(String[] args) throws Exception {
    Class<?> tc=Class.forName("com.aurora.music.playback.engine.PrecisionEffectsKernelTest");test=tc.getConstructor().newInstance();params=tc.getDeclaredMethod("richParameters");params.setAccessible(true);
    Class<?> rc=Class.forName("com.aurora.music.playback.engine.LegacyFloatEffectsReference");Object ref=rc.getField("INSTANCE").get(null);Method legacy=rc.getMethod("process",double[].class,DspParams.class,int.class);
    kotlin.random.Random random=kotlin.random.RandomKt.Random(29491);
    double[] input=new double[24000];for(int i=0;i<input.length;i++)input[i]=random.nextInt(-8192,8193)/32768f;
    DspParams p=rich();
    for(String kind:new String[]{"full","eq","graphic","effects"}){
      DspParams v=kind.equals("full")?p:variant(p,kind);
      double[] old=(double[])legacy.invoke(ref,input,v,48000);
      double[] precise=run(input,PrecisionDspCoeffBuilder.INSTANCE.build(v,48000));
      double[] sameCoefficients=run(input,promoted(v));
      metric(kind+" total",precise,old);
      metric(kind+" Double math with legacy coefficients vs legacy",sameCoefficients,old);
      metric(kind+" coefficient calculation only",precise,sameCoefficients);
      if(kind.equals("eq")){double[] reference=transposed(input,PrecisionDspCoeffBuilder.INSTANCE.build(v,48000));
        metric("EQ Double DFI vs independent DFII",precise,reference);
        metric("EQ legacy Float vs independent DFII",old,reference);
      }
    }
  }
}
