package mill.contrib.packaging

import java.io.IOException
import java.nio.file.{Files, Paths}
import java.util.zip.{ZipEntry, ZipOutputStream}

import mill._
import mill.define.Sources
import mill.eval.PathRef
import mill.scalalib._
import mill.define.Task
import ammonite.ops._

trait Packaging extends JavaModule {

  // copy dependent project jars
  def projectJars = T {
    Task
      .traverse(moduleDeps)(m =>
        T.task {
          (s"${m.artifactName()}.jar", PathRef(m.jar().path))
        })()
  }

  //copy project jar
  def projectVar = T {
    (s"${artifactName()}.jar", PathRef(jar().path))
  }


  // copy dependency jars
  def dependencyJars = T {
    runClasspath()
      .map(_.path)
      .filter(path => exists(path) && path.name.endsWith(".jar"))
      .map(path => (path.segments.last, PathRef(path)))
  }



  def prepareScript = T {

    val jars: Seq[String] = (projectJars() ++ dependencyJars() :+ projectVar()).map(_._1)
    val mainClass = finalMainClass()
    val moduleDepNames = jars.map(jar => s"$$lib_dir/${jar}").mkString(":")
    val appCP = s"""declare -r app_classpath="$moduleDepNames""""

    //insert into template script
    val newScript = TemplateScripts.bash(mainClass, appCP, mainClass)
    write(T.ctx().dest / "template-script", "")
    write.over(T.ctx().dest / "template-script", newScript)

    T.ctx().dest / "template-script"
  }

  def applicationIni = T {
    write(T.ctx().dest / "conf" / "application.ini", "")
    forkArgs().foreach( option =>
      write.append(T.ctx().dest / "application.ini", option)
    )
    PathRef(T.ctx().dest / "application.ini")
  }

  def prepareEnv = T {
    implicit val currentDir = T.ctx().dest
    val libDir =  T.ctx().dest / 'lib
    val bin =  T.ctx().dest / 'bin
    val conf = T.ctx().dest / 'conf

    mkdir(libDir)
    mkdir(bin)
    mkdir(conf)

    //copy jars in lib folder
    (projectJars() ++ dependencyJars() :+ projectVar()).foreach(
      jar => cp.over(jar._2.path, libDir / jar._1)
    )

    //load and edit template-script
    val scriptDst = prepareScript()

    //copy script
    cp.into(scriptDst, bin)

    // copy application.ini
    cp.into(applicationIni().path, conf)

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
