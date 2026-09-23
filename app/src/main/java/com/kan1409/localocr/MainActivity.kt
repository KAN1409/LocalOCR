package com.kan1409.localocr

import android.app.Activity
import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.*
import com.google.ai.edge.litertlm.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
 private lateinit var status:TextView; private lateinit var output:TextView; private lateinit var image:ImageView
 private lateinit var progress:ProgressBar; private lateinit var runButton:Button
 private var imageFile:File?=null; private var cameraUri:Uri?=null
 private val modelFile by lazy { File(filesDir,"Qwen2-VL-2B.litertlm") }
 private val modelUrl="https://huggingface.co/litert-community/Qwen2-VL-2B/resolve/main/Qwen2-VL-2B.litertlm?download=true"

 override fun onCreate(b:Bundle?){super.onCreate(b);buildUi();refresh()}
 private fun buildUi(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,48,32,32)}
  val title=TextView(this).apply{text="LocalOCR";textSize=28f}
  status=TextView(this).apply{textSize=14f;setPadding(0,16,0,12)}
  progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.GONE}
  val download=Button(this).apply{text="Download Qwen2-VL-2B model (~1.78 GB)";setOnClickListener{downloadModel()}}
  val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  val gallery=Button(this).apply{text="Gallery";setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},10)}}
  val camera=Button(this).apply{text="Camera";setOnClickListener{openCamera()}}
  row.addView(gallery,LinearLayout.LayoutParams(0,-2,1f));row.addView(camera,LinearLayout.LayoutParams(0,-2,1f))
  image=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.CENTER_INSIDE;minimumHeight=280}
  runButton=Button(this).apply{text="Extract text (offline)";isEnabled=false;setOnClickListener{runOcr()}}
  output=TextView(this).apply{textSize=17f;setTextIsSelectable(true);movementMethod=ScrollingMovementMethod();textDirection=View.TEXT_DIRECTION_FIRST_STRONG;setPadding(16,20,16,20);minHeight=250}
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  val copy=Button(this).apply{text="Copy";setOnClickListener{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("OCR",output.text));Toast.makeText(this@MainActivity,"Copied",Toast.LENGTH_SHORT).show()}}
  val share=Button(this).apply{text="Share";setOnClickListener{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,output.text.toString())},"Share OCR text"))}}
  actions.addView(copy,LinearLayout.LayoutParams(0,-2,1f));actions.addView(share,LinearLayout.LayoutParams(0,-2,1f))
  root.addView(title);root.addView(status);root.addView(progress);root.addView(download);root.addView(row);root.addView(image);root.addView(runButton);root.addView(output);root.addView(actions)
  setContentView(ScrollView(this).apply{addView(root)})
 }
 private fun refresh(){status.text=if(modelFile.exists()&&modelFile.length()>1_000_000_000L)"Model ready • "+(modelFile.length()/1024/1024)+" MB • OCR runs offline" else "Model not downloaded yet. First setup needs internet once.";runButton.isEnabled=imageFile!=null&&modelFile.exists()}
 private fun downloadModel(){
  if(modelFile.exists()&&modelFile.length()>1_000_000_000L){refresh();return}
  progress.visibility=View.VISIBLE;status.text="Downloading model… keep the app open."
  thread{try{
   val part=File(filesDir,modelFile.name+".part");val c=URL(modelUrl).openConnection() as HttpURLConnection
   c.instanceFollowRedirects=true;c.connectTimeout=20000;c.readTimeout=30000;val total=c.contentLengthLong
   c.inputStream.use{input->part.outputStream().use{out->val buf=ByteArray(1024*1024);var done=0L;while(true){val n=input.read(buf);if(n<0)break;out.write(buf,0,n);done+=n;if(total>0)runOnUiThread{progress.progress=((done*100)/total).toInt();status.text="Downloading… "+(done/1024/1024)+" / "+(total/1024/1024)+" MB"}}}}
   if(!part.renameTo(modelFile)){part.copyTo(modelFile,true);part.delete()};runOnUiThread{progress.visibility=View.GONE;refresh()}
  }catch(e:Exception){runOnUiThread{progress.visibility=View.GONE;status.text="Download failed: "+e.message}}}
 }
 private fun openCamera(){val v=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"localocr_"+System.currentTimeMillis()+".jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")};cameraUri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply{putExtra(MediaStore.EXTRA_OUTPUT,cameraUri)},11)}
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(c!=RESULT_OK)return;val u=if(r==10)d?.data else cameraUri;if(u!=null)loadImage(u)}
 private fun loadImage(u:Uri){try{val f=File(cacheDir,"ocr_input_"+System.currentTimeMillis()+".jpg");contentResolver.openInputStream(u)!!.use{a->f.outputStream().use{a.copyTo(it)}};imageFile=f;image.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath));output.text="";refresh()}catch(e:Exception){status.text="Image error: "+e.message}}
 private fun runOcr(){
  val input=imageFile?:return;if(!modelFile.exists())return;runButton.isEnabled=false;progress.visibility=View.VISIBLE;progress.isIndeterminate=true;output.text="";status.text="Loading local AI model…"
  thread{var engine:Engine?=null;try{
   Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
   engine=try{Engine(EngineConfig(modelPath=modelFile.absolutePath,backend=Backend.GPU(),visionBackend=Backend.GPU(),cacheDir=cacheDir.absolutePath,maxNumImages=1)).also{it.initialize()}}
   catch(_:Throwable){Engine(EngineConfig(modelPath=modelFile.absolutePath,backend=Backend.CPU(),visionBackend=Backend.CPU(),cacheDir=cacheDir.absolutePath,maxNumImages=1)).also{it.initialize()}}
   runOnUiThread{status.text="Reading image offline…"}
   engine.createConversation().use{conv->
    val p="Extract ALL visible text exactly as written. Preserve Arabic and English exactly. Do not translate, summarize, correct, explain, or invent. Preserve numbers, punctuation, line breaks, and reading order. For tables preserve rows and columns in Markdown. Return ONLY extracted text. If no text is visible, return [NO TEXT]."
    val response=conv.sendMessage(Contents.of(Content.Text(p),Content.ImageFile(input.absolutePath)),maxOutputToken=2048)
    runOnUiThread{output.text=response.toString();status.text="Done • local/offline inference"}
   }
  }catch(e:Throwable){runOnUiThread{output.text="";status.text="OCR failed: "+(e.message?:e.javaClass.simpleName)}}finally{try{engine?.close()}catch(_:Throwable){};runOnUiThread{progress.visibility=View.GONE;progress.isIndeterminate=false;runButton.isEnabled=true}}}
 }
}
