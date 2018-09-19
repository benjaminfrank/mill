package mill.contrib

import java.io.IOException
import java.nio.file.{Files, Paths}
import java.util.zip.{ZipEntry, ZipOutputStream}

import mill._
import mill.define.Sources
import mill.eval.PathRef
import mill.scalalib._
import mill.define.Task
import ammonite.ops._

import scala.io.Source

trait Packaging extends JavaModule{

  // copy dependent project jars
  def projectJars = T {
    Task
      .traverse(moduleDeps)(m =>
        T.task {
          (s"${m.artifactName()}.jar", m.jar())
        })()
      .map(aj => {
        // properly name artifacts before copying
        val fileToWrite = aj._2.path / up / aj._1
        mv(aj._2.path, fileToWrite)
        PathRef(fileToWrite, true)
      })
  }

  //copy project jar
  def projectVar = T {
    val dst = T.ctx().dest / artifactName().mkString.concat(".jar")
    cp.over(jar().path, dst)
    PathRef(dst)
  }

  // copy dependency jars
  def dependencyJars = T {
    runClasspath()
      .map(_.path)
      .filter(path => exists(path) && path.name.endsWith(".jar"))
      .map(PathRef(_))
  }


  def prepareScript = T {

    val jars: Seq[PathRef] = projectJars() ++ dependencyJars() :+ projectVar()
    val mainClass = finalMainClass()


    val moduleDepNames = jars.map(jar => s"$$lib_dir/${jar.path.segments.last}").mkString(":")
    val appCP = s"""declare -r app_classpath="$moduleDepNames""""
    val script: String = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("template-script")).mkString
    val newScript = script.replace("${{app_mainclass}}", mainClass).replace("${{template_declares}}", appCP).replace("${{available_main_classes}}", mainClass)
    write(T.ctx().dest / "template-script", "")
    write.over(T.ctx().dest / "template-script", newScript)

    T.ctx().dest / "template-script"
  }

  def applicationIni = T {
    write(T.ctx().dest / "application.ini", "")
    javacOptions().foreach(option =>
      write.append(T.ctx().dest / "application.ini", option)
    )
    PathRef(T.ctx().dest / "application.ini")
  }

  def prepareEnv = T {
    implicit val currentDir = T.ctx().dest
    val libDir =  T.ctx().dest / 'lib
    val bin =  T.ctx().dest / 'bin

    mkdir(libDir)
    mkdir(bin)

    //copy jars in lib folder
    (projectJars() ++ dependencyJars() :+ projectVar()).foreach(
      jar => cp.over(jar.path, libDir / jar.path.segments.last)
    )

    //load and edit template-script
    val scriptDst = prepareScript()

    //copy script
    cp.into(scriptDst, T.ctx().dest / 'bin)

    // copy application.ini
    cp.into(applicationIni().path, T.ctx().dest)

    PathRef(T.ctx().dest)
  }

  def packageZip = T {

    //get source path
    val env = prepareEnv()

    //zip directory located at source path
    val p: java.nio.file.Path= Files.createFile(Paths.get((T.ctx().dest / "out.zip").toIO.getAbsolutePath))
    try {
      val zs: ZipOutputStream = new ZipOutputStream(Files.newOutputStream(p))
      val pp: java.nio.file.Path = Paths.get(env.path.toIO.getAbsolutePath)
      Files.walk(pp)
        .filter(path => !Files.isDirectory(path))
        .forEach(path => {
          val zipEntry: ZipEntry = new ZipEntry(pp.relativize(path).toString)
          try {
            zs.putNextEntry(zipEntry)
            Files.copy(path, zs)
            zs.closeEntry()
          } catch {
            case e: Throwable => System.err.println(e)
          }
        })
      zs.finish()
      zs.close()
    } catch {
      case e: Throwable => System.err.println(e)
    }

    PathRef(T.ctx().dest / "out.zip")
  }


}
