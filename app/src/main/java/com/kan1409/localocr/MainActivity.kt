package com.kan1409.localocr

import android.app.DownloadManager
import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

class MainActivity : ComponentActivity() {
 private lateinit var status:TextView; private lateinit var output:TextView; private lateinit var image:ImageView
 private lateinit var progress:ProgressBar; private lateinit var runButton:Button
 private var imageFile:File?=null; private var cameraUri:Uri?=null
 private val modelFile by lazy { File(getExternalFilesDir(null) ?: filesDir,"Qwen2-VL-2B.litertlm") }
 private val modelUrl="https://huggingface.co/litert-community/Qwen2-VL-2B/resolve/5a03c8597ec02ec3c84cde3f1fd043688396bde5/Qwen2-VL-2B.litertlm?download=true"
 private val prefs by lazy { getSharedPreferences("localocr",MODE_PRIVATE) }

 private val pickImage=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){it?.let(::loadImage)}
 private val importModel=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(::importModel)}
 private val exportModel=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){u->if(u!=null)exportModel(u)}
 private val takePicture=registerForActivityResult(ActivityResultContracts.TakePicture()){ok->
  val u=cameraUri
  if(ok&&u!=null)loadImage(u) else if(u!=null)contentResolver.delete(u,null,null)
  cameraUri=null
 }

 override fun onCreate(b:Bundle?){super.onCreate(b);buildUi();refresh();checkDownload()}
 private fun buildUi(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,48,32,32)}
  val title=TextView(this).apply{text="LocalOCR";textSize=28f}
  status=TextView(this).apply{textSize=14f;setPadding(0,16,0,12)}
  progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;visibility=View.GONE}
  val download=Button(this).apply{text="Download model (~1.78 GB)";setOnClickListener{downloadModel()}}
  val import=Button(this).apply{text="Import existing .litertlm";setOnClickListener{importModel.launch(arrayOf("application/octet-stream","*/*"))}}
  val export=Button(this).apply{text="Backup model";setOnClickListener{if(modelReady())exportModel.launch(modelFile.name) else Toast.makeText(this@MainActivity,"Model not ready",Toast.LENGTH_SHORT).show()}}
  val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  val gallery=Button(this).apply{text="Gallery";setOnClickListener{pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}}
  val camera=Button(this).apply{text="Camera";setOnClickListener{openCamera()}}
  row.addView(gallery,LinearLayout.LayoutParams(0,-2,1f));row.addView(camera,LinearLayout.LayoutParams(0,-2,1f))
  image=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.CENTER_INSIDE;minimumHeight=280}
  runButton=Button(this).apply{text="Extract text (offline)";isEnabled=false;setOnClickListener{runOcr()}}
  output=TextView(this).apply{textSize=17f;setTextIsSelectable(true);movementMethod=ScrollingMovementMethod();textDirection=View.TEXT_DIRECTION_FIRST_STRONG;setPadding(16,20,16,20);minHeight=250}
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  val copy=Button(this).apply{text="Copy";setOnClickListener{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("OCR",output.text));Toast.makeText(this@MainActivity,"Copied",Toast.LENGTH_SHORT).show()}}
  val share=Button(this).apply{text="Share";setOnClickListener{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,output.text.toString())},"Share OCR text"))}}
  actions.addView(copy,LinearLayout.LayoutParams(0,-2,1f));actions.addView(share,LinearLayout.LayoutParams(0,-2,1f))
  listOf(title,status,progress,download,import,export,row,image,runButton,output,actions).forEach(root::addView)
  setContentView(ScrollView(this).apply{addView(root)})
 }
 private fun modelReady()=modelFile.exists()&&modelFile.length()>1_700_000_000L
 private fun refresh(){status.text=if(modelReady())"Model ready • "+(modelFile.length()/1024/1024)+" MB • OCR runs offline" else "Model not ready. Download once or import a backup.";runButton.isEnabled=imageFile!=null&&modelReady()}
 private fun downloadModel(){
  if(modelReady()){refresh();return}
  val dm=getSystemService(DOWNLOAD_SERVICE) as DownloadManager
  if(modelFile.exists())modelFile.delete()
  val req=DownloadManager.Request(Uri.parse(modelUrl)).setTitle("LocalOCR model").setDescription("Qwen2-VL-2B").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationUri(Uri.fromFile(modelFile))
  val id=dm.enqueue(req);prefs.edit().putLong("download_id",id).apply();status.text="Model download started in Android Download Manager.";progress.visibility=View.VISIBLE;checkDownload()
 }
 private fun checkDownload(){
  val id=prefs.getLong("download_id",-1);if(id<0)return
  val dm=getSystemService(DOWNLOAD_SERVICE) as DownloadManager
  lifecycleScope.launch{
   while(true){
    val state=withContext(Dispatchers.IO){dm.query(DownloadManager.Query().setFilterById(id)).use{q->
     if(!q.moveToFirst())return@use Triple(-1,0L,0L)
     Triple(q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
    }}
    val (s,d,t)=state
    if(t>0){progress.visibility=View.VISIBLE;progress.progress=((d*100)/t).toInt();status.text="Downloading model… "+d/1024/1024+" / "+t/1024/1024+" MB"}
    if(s==DownloadManager.STATUS_SUCCESSFUL){prefs.edit().remove("download_id").apply();progress.visibility=View.GONE;refresh();break}
    if(s==DownloadManager.STATUS_FAILED||s==-1){prefs.edit().remove("download_id").apply();progress.visibility=View.GONE;status.text="Model download failed. Retry to resume with Android Download Manager.";break}
    kotlinx.coroutines.delay(1000)
   }
  }
 }
 private fun openCamera(){
  val v=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"localocr_"+System.currentTimeMillis()+".jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")}
  cameraUri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v)
  cameraUri?.let(takePicture::launch) ?: run{status.text="Could not create camera image"}
 }
 private fun importModel(u:Uri){lifecycleScope.launch{progress.visibility=View.VISIBLE;status.text="Validating and importing model…";try{
  withContext(Dispatchers.IO){val part=File(modelFile.parentFile,modelFile.name+".import");contentResolver.openInputStream(u)!!.use{a->part.outputStream().use{a.copyTo(it)}};require(part.length()>1_700_000_000L){"Selected file is too small to be Qwen2-VL-2B"};if(modelFile.exists())modelFile.delete();check(part.renameTo(modelFile)||run{part.copyTo(modelFile,true);part.delete();true})}
  refresh()
 }catch(e:Exception){status.text="Import failed: "+e.message}finally{progress.visibility=View.GONE}}}
 private fun exportModel(u:Uri){lifecycleScope.launch{progress.visibility=View.VISIBLE;status.text="Backing up model…";try{withContext(Dispatchers.IO){contentResolver.openOutputStream(u,"w")!!.use{out->modelFile.inputStream().use{it.copyTo(out)}}};status.text="Model backup complete."}catch(e:Exception){status.text="Backup failed: "+e.message}finally{progress.visibility=View.GONE}}}
 private fun loadImage(u:Uri){lifecycleScope.launch{try{val f=withContext(Dispatchers.IO){File(cacheDir,"ocr_input_"+System.currentTimeMillis()+".png").also{dst->contentResolver.openInputStream(u)!!.use{a->dst.outputStream().use{a.copyTo(it)}}}};imageFile=f;val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(f.absolutePath,o);require(o.outWidth>0&&o.outHeight>0){"Unsupported image"};var s=1;while(o.outWidth/s>2400||o.outHeight/s>2400)s*=2;image.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath,BitmapFactory.Options().apply{inSampleSize=s}));output.text="";refresh()}catch(e:Exception){status.text="Image error: "+e.message}}}
 private fun setStage(message:String){runOnUiThread{status.text=message}}
 private fun shortError(t:Throwable)=buildString{
  append(t.javaClass.simpleName);t.message?.let{append(": ").append(it)}
  var c=t.cause;var n=0;while(c!=null&&n++<3){append("\nCaused by ").append(c.javaClass.simpleName);c.message?.let{append(": ").append(it)};c=c.cause}
 }
 private fun runOcr(){
  val input=imageFile?:return;if(!modelReady())return
  runButton.isEnabled=false;progress.visibility=View.VISIBLE;progress.isIndeterminate=true;output.text=""
  lifecycleScope.launch{
   var engine:Engine?=null
   val started=android.os.SystemClock.elapsedRealtime()
   val diag=StringBuilder("LocalOCR runtime diagnostics\nModel: ").append(modelFile.length()/1024/1024).append(" MB\n")
   try{
    val result=withContext(Dispatchers.IO){
     Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
     fun config(b:Backend)=EngineConfig(modelPath=modelFile.absolutePath,backend=b,visionBackend=b,cacheDir=cacheDir.absolutePath,maxNumImages=1,maxNumTokens=4096)
     var gpuError:Throwable?=null
     setStage("Initializing GPU…")
     val g0=android.os.SystemClock.elapsedRealtime()
     engine=try{
      val e=Engine(config(Backend.GPU()))
      try{withTimeout(45_000){e.initialize()}}catch(t:Throwable){try{e.close()}catch(_:Throwable){};throw t}
      diag.append("GPU init: OK ").append(android.os.SystemClock.elapsedRealtime()-g0).append(" ms\n");e
     }catch(t:Throwable){
      if(t is OutOfMemoryError)throw t
      gpuError=t;diag.append("GPU init: FAILED ").append(android.os.SystemClock.elapsedRealtime()-g0).append(" ms\n").append(shortError(t)).append("\n")
      null
     }
     if(engine==null){
      setStage("GPU unavailable • initializing CPU fallback…")
      val c0=android.os.SystemClock.elapsedRealtime()
      engine=try{
       val e=Engine(config(Backend.CPU()))
       try{withTimeout(120_000){e.initialize()}}catch(t:Throwable){try{e.close()}catch(_:Throwable){};throw t}
       diag.append("CPU multimodal init: OK ").append(android.os.SystemClock.elapsedRealtime()-c0).append(" ms\n");e
      }catch(t:Throwable){
       if(t is OutOfMemoryError)throw t
       diag.append("CPU multimodal init: FAILED ").append(android.os.SystemClock.elapsedRealtime()-c0).append(" ms\n").append(shortError(t)).append("\n")
       t.addSuppressed(gpuError);throw t
      }
     }
     setStage("Model loaded • processing image…")
     engine!!.createConversation().use{conv->
      val p="Extract ALL visible text exactly as written. Preserve Arabic and English exactly. Do not translate, summarize, correct, explain, or invent. Preserve numbers, punctuation, line breaks, and reading order. For tables preserve rows and columns in Markdown. Inspect the entire image carefully from top to bottom and do not omit small, low-contrast, mixed-direction, or partially visible text. Arabic and English are equally important: transcribe BOTH languages, including code-switched lines. Return ONLY extracted text. If no text is visible, return [NO TEXT]."
      setStage("Generating OCR text…")
      conv.sendMessage(Contents.of(Content.Text(p),Content.ImageFile(input.absolutePath)),maxOutputToken=4096).toString()
     }
    }
    output.text=result
    status.text="Done • local/offline • "+((android.os.SystemClock.elapsedRealtime()-started)/1000)+" s"
   }catch(t:Throwable){
    val msg=if(t is TimeoutCancellationException)"Initialization timed out" else "OCR failed"
    diag.append(msg).append("\n").append(shortError(t)).append("\nTotal: ").append(android.os.SystemClock.elapsedRealtime()-started).append(" ms")
    output.text=diag.toString();status.text="$msg • diagnostics below (Copy/Share)"
   }finally{
    withContext(Dispatchers.IO){try{engine?.close()}catch(_:Throwable){}}
    progress.visibility=View.GONE;progress.isIndeterminate=false;runButton.isEnabled=true
   }
  }
 }
}
