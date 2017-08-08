package net.sansa_stack.ml.spark.clustering

import org.apache.spark.rdd.RDD
import org.apache.spark.graphx.{ Graph, EdgeDirection }
import scala.math.BigDecimal
import org.apache.spark.sql.SparkSession
import scala.reflect.runtime.universe._
import scopt.OptionParser
import org.apache.log4j.{ Level, Logger }
import org.apache.spark.mllib.util.MLUtils
import java.io.{ FileReader, FileNotFoundException, IOException }
import org.apache.spark.mllib.linalg.Vectors
import java.lang.{ Long => JLong }
import java.lang.{ Long => JLong }
import breeze.linalg.{ squaredDistance, DenseVector, Vector }
import org.apache.spark.sql.SparkSession
import org.apache.spark.graphx.GraphLoader
import scala.util.control.Breaks._
import org.apache.jena.datatypes.{ RDFDatatype, TypeMapper }
import org.apache.jena.graph.{ Node => JenaNode, Triple => JenaTriple, _ }
import org.apache.jena.riot.writer.NTriplesWriter
import org.apache.jena.riot.{ Lang, RDFDataMgr }
import org.apache.jena.graph.{ Node_ANY, Node_Blank, Node_Literal, Node_URI, Node => JenaNode, Triple => JenaTriple }
import org.apache.jena.vocabulary.RDF
import java.io.ByteArrayInputStream
import org.apache.spark.rdd.PairRDDFunctions
import org.apache.spark.SparkContext._
import org.apache.spark.graphx._
import org.apache.jena.util._
import java.io.StringWriter
import java.io._

object BorderFlow {
  def apply(spark: SparkSession, edgesInputPath: String) = {

    /*
	 * Load the RDF file and convert it to a graph.
	 */

    val RDFfile = spark.sparkContext.textFile(edgesInputPath).map(line =>
      RDFDataMgr.createIteratorTriples(new ByteArrayInputStream(line.getBytes), Lang.NTRIPLES, null).next())

    val r = RDFfile.map(f => {
      val s = f.getSubject.getURI
      val p = f.getPredicate.getURI
      val o = f.getObject.getURI

      (s, p, o)
    })

    val v1 = r.map(f => f._1)
    val v2 = r.map(f => f._3)
    val indexedmap = (v1.union(v2)).distinct().zipWithIndex()

    val vertices: RDD[(VertexId, String)] = indexedmap.map(x => (x._2, x._1))
    val _iriToId: RDD[(String, VertexId)] = indexedmap.map(x => (x._1, x._2))

    val tuples = r.keyBy(f => f._1).join(indexedmap).map({
      case (k, ((s, p, o), si)) => (o, (si, p))
    })

    val edgess: RDD[Edge[String]] = tuples.join(indexedmap).map({
      case (k, ((si, p), oi)) => Edge(si, oi, p)
    })
    edgess.foreach(f => println(f))

    val graph = org.apache.spark.graphx.Graph(vertices, edgess)

    /*
	 * undirected graph : orient =0
	 * directed graph : orient =1.
	 * 
	 * Jaccard similarity measure : selectYourSimilarity = 0
	 * Batet similarity measure : selectYourSimilarity = 1
	 * Rodríguez and Egenhofer similarity measure : selectYourSimilarity = 2
	 * The Contrast model similarity : selectYourSimilarity = 3
	 * The Ratio model similarity : selectYourSimilarity = 4
	 */
    val orient = 0
    val selectYourSimilarity = 0
    val Heuristics = 0

    def clusterRdd(): List[List[Long]] = {
      graphXinBorderFlow(orient, selectYourSimilarity, Heuristics)
    }

    /*
	 * Computes different similarities function for a given graph @graph.
	 */
    def graphXinBorderFlow(e: Int, f: Int, g: Int): List[List[Long]] = {

      val edge = graph.edges.collect()
      val vertex = graph.vertices.count().toDouble

      def neighbors(d: Int): VertexRDD[Array[VertexId]] = {
        var neighbor: VertexRDD[Array[VertexId]] = graph.collectNeighborIds(EdgeDirection.Either)

        if (d == 1) {
          neighbor = graph.collectNeighborIds(EdgeDirection.Out)
        }
        neighbor
      }
      val neighbor = neighbors(e)

      val sort = neighbor.map(f => {
        val x = f._1.toLong

        (x)
      })

      var X: List[Long] = sort.collect().toList

      /*
	 * Computing logarithm based 2
	 */
      val LOG2 = math.log(2)
      val log2 = { x: Double => math.log(x) / LOG2 }

      /*
	 * Difference between two set of vertices, used in different similarity measures
	 */

      def difference(a: Long, b: Long): Double = {
        val ansec = neighbor.lookup(a).distinct.head.toSet
        val ansec1 = neighbor.lookup(b).distinct.head.toSet
        if (ansec.isEmpty) { return 0.0 }
        val differ = ansec.diff(ansec1)
        if (differ.isEmpty) { return 0.0 }

        differ.size.toDouble
      }

      /*
	 * Intersection of two set of vertices, used in different similarity measures
	 */

      def intersection(a: Long, b: Long): Double = {
        val inters = neighbor.lookup(a).distinct.head.toSet
        val inters1 = neighbor.lookup(b).distinct.head.toSet
        if (inters.isEmpty || inters1.isEmpty) { return 0.0 }
        val rst = inters.intersect(inters1).toArray
        if (rst.isEmpty) { return 0.0 }
        rst.size.toDouble
      }

      /*
			 * Union of two set of vertices, used in different similarity measures
			 */

      def union(a: Long, b: Long): Double = {
        val inters = neighbor.lookup(a).distinct.head.toSet
        val inters1 = neighbor.lookup(b).distinct.head.toSet
        val rst = inters.union(inters1).toArray
        if (rst.isEmpty) { return 0.0 }

        rst.size.toDouble
      }

      def selectSimilarity(a: Long, b: Long, c: Int): Double = {
        var s = 0.0
        if (c == 0) {

          /*
			 * Jaccard similarity measure
			 */

          val sim = intersection(a, b) / union(a, b).toDouble
          if (sim == 0.0) { s = (1 / vertex) }
          else { s = sim }

        }

        if (c == 1) {

          /*
			 * Rodríguez and Egenhofer similarity measure
			 */

          var g = 0.8

          val sim = (intersection(a, b) / ((g * difference(a, b)) + ((1 - g) * difference(b, a)) + intersection(a, b))).toDouble.abs
          if (sim == 0.0) { s = (1 / vertex) }
          else { s = sim }

        }
        if (c == 2) {
          /*
			 * The Ratio model similarity
			 */
          var alph = 0.5
          var beth = 0.5

          val sim = ((intersection(a, b)) / ((alph * difference(a, b)) + (beth * difference(b, a)) + intersection(a, b))).toDouble.abs
          if (sim == 0.0) { s = (1 / vertex) }
          else { s = sim }

        }

        if (c == 3) {
          /*
			 * Batet similarity measure 
			 */

          val cal = 1 + ((difference(a, b) + difference(b, a)) / (difference(a, b) + difference(b, a) + intersection(a, b))).abs
          val sim = log2(cal.toDouble)
          if (sim == 0.0) { s = (1 / vertex) }
          else { s = sim }

        }
        s
      }

      val weightedGraph = edge.map { x =>
        {

          val x1 = x.srcId.toLong

          val x2 = x.dstId.toLong

          (x1, x2, selectSimilarity(x1, x2, f).abs)
        }
      }

      def findingSimilarity(a: Long, b: Long): Double = {
        var f3 = 0.0
        weightedGraph.map(f => {
          if ((f._1 == a && f._2 == b) || (f._1 == b && f._2 == a)) { f3 = f._3 }

        })
        f3
      }

      //computing f(X,V) for Heuristics BorderFlow

      def fOmega(x: List[Long], v: Long): Double = {
        var numberFlow = 0

        def listOfB(b: List[Long]): List[Long] = {

          var listN: List[Long] = List()

          for (k <- 0 until b.length) yield {
            val nX = neighbor.lookup(b(k)).distinct.head

            val nXa = nX.diff(b).toList
            if (nXa.size > 0) {
              listN = listN.::(b(k))

            }
          }
          (listN)
        }

        val b = listOfB(x)
        val VX = X.diff(x)
        var jaccardBV = 0.0
        if (b.size == 0) return 0.0
        for (i <- 0 until b.length) yield {

          jaccardBV = jaccardBV.+(findingSimilarity(b(i), v).abs)

        }

        var jaccardVXV = 0.0

        for (i <- 0 until VX.length) yield {
          if (VX(i) != v) {

            jaccardVXV = jaccardVXV.+(findingSimilarity(VX(i), v).abs)

          }

        }

        (jaccardVXV / jaccardBV)
        /*  
         *  without similarity
         val nv = neighborSort.lookup(v).distinct.head.toSet
         val nvX = nv.intersect(X.toSet)
         val nvx = nvX.toList.diff(x).size
        
         
          for(k <- 0 until x.length) yield{
            if(x.length>0){
           
           val xk = x(k)
           val bX = neighborSort.lookup(xk).distinct.head.toSet
           val bxX = bX.intersect(X.toSet)
           
           if(bxX.toList.diff(x).size > 0 && bxX.toList.diff(x).contains(v)) {
             numberFlow = numberFlow + 1
             }
           
            }       
           
         }
        
        ( 1/(numberFlow.toDouble/ nvx.toDouble))
        * 
        */

      }

      //computing F(X) for BorderFlow 

      def fX(x: List[Long]): Double = {
        var jaccardX = 0.0
        var jaccardN = 0.0

        def listOfN(b: List[Long]): List[Long] = {

          var listN: List[Long] = List()
          if (b.length > 0) {
            for (k <- 0 until b.length) yield {
              val nX = neighbor.lookup(b(k)).distinct.head

              val nXa = nX.diff(b).toList
              listN = listN.union(nXa).distinct

            }
          }
          (listN)
        }

        def listOfB(b: List[Long]): List[Long] = {

          var listN: List[Long] = List()

          for (k <- 0 until b.length) yield {
            val nX = neighbor.lookup(b(k)).distinct.head

            val nXa = nX.diff(b).toList
            if (nXa.size > 0) {
              listN = listN.::(b(k))

            }
          }
          (listN)
        }

        val n = listOfN(x)
        val b = listOfB(x)

        if (b.size == 0) return 0.0

        def makeomegaB(b: List[Long], c: List[Long]): Double = {

          var listN: List[Long] = List()

          for (k <- 0 until b.length) yield {
            val nX = neighbor.lookup(b(k)).distinct.head

            listN = listN.++(((nX.intersect(c).toList)))
          }
          listN.size.toDouble
        }

        for (i <- 0 until b.length) yield {
          for (j <- 0 until x.length) yield {
            if (b(i) != x(j)) {
              jaccardX = jaccardX.+(findingSimilarity(b(i), x(j)).abs)

            }
          }
        }

        for (i <- 0 until b.length) yield {
          for (j <- 0 until n.length) yield {

            jaccardN = jaccardN.+(findingSimilarity(b(i), n(j)).abs)

          }
        }

        (jaccardX / jaccardN)

        //  ( ( listOfNb(listOfB(x)).intersect(x)).size.toDouble / (listOfNb(listOfB(x)).intersect(listOfN(x))).size.toDouble)

        //(makeomegaB(b,x) / makeomegaB(b,n))
      }

      def omega(u: Long, x: List[Long]): Double = {

        def listOfN(b: List[Long]): List[Long] = {

          var listN: List[Long] = List()

          for (k <- 0 until b.length) yield {
            val nX = neighbor.lookup(b(k)).distinct.head

            val nXa = nX.diff(b).toList
            listN = listN.union(nXa).distinct

          }
          (listN)
        }
        val n = listOfN(x)
        var jaccardNU = 0.0

        for (i <- 0 until n.length) yield {
          if (n(i) != u) {

            jaccardNU = jaccardNU.+(findingSimilarity(u, n(i)).abs)

          }

        }
        /* 
         * without similarity
         val nu = neighborSort.lookup(u).distinct.head.toSet
         val nuX = nu.intersect(X.toSet).toList
        ( (nuX.intersect(listOfN(x))).size.toDouble)
         
         */
        jaccardNU

      }

      /*
	 * Use Heuristics method for producing clusters.
	 */

      def heuristicsCluster(a: List[Long]): List[Long] = {
        var nj = 0.0
        var minF = 100000000000000.0
        var appends = a

        def neighborsOfList(c: List[Long]): List[Long] = {

          var listN: List[Long] = List()

          for (k <- 0 until c.length) yield {
            val nX = neighbor.lookup(c(k)).distinct.head

            val nXa = nX.diff(c).toList
            listN = listN.union(nXa).distinct

          }

          (listN)
        }

        var maxFf = fX(appends)

        val neighborsOfX = neighborsOfList(appends)

        if (neighborsOfX.size <= 0) return appends
        else {
          for (k <- 0 until neighborsOfX.length) yield {

            val f = fOmega(appends, neighborsOfX(k))

            if (f < minF) {
              minF = f
              nj = neighborsOfX(k)

            }

          }

          appends = appends.::(nj.toLong)

          if (neighborsOfList(appends).size == 0) return appends
          if (fX(appends) < maxFf) return appends.tail

          heuristicsCluster(appends)

        }

      }

      /*
	 * Use Non-Heuristics(normal) method for producing clusters.
	 */

      def nonHeuristicsCluster(a: List[Long], d: List[Long]): List[Long] = {
        var nj: List[Long] = List()
        var nj2: List[Long] = List()
        var maxF = 0.0
        var appends = a

        var maxfcf = 0.0
        var compare = d

        def neighborsOfList(c: List[Long]): List[Long] = {

          var listN: List[Long] = List()

          for (k <- 0 until c.length) yield {
            val nX = neighbor.lookup(c(k)).distinct.head

            val nXa = nX.diff(c).toList
            listN = listN.union(nXa).distinct

          }

          (listN)
        }

        var maxFf = fX(appends)

        val neighborsOfX = neighborsOfList(appends)

        if (neighborsOfX.size <= 0) return appends

        for (k <- 0 until neighborsOfX.length) yield {

          appends = appends.::(neighborsOfX(k))
          val f = fX(appends)

          if (f >= maxF && f >= maxFf) {
            if (f == maxF) {
              maxF = f
              nj = nj.::(neighborsOfX(k))
              appends = appends.tail
            } else {
              maxF = f
              nj = List(neighborsOfX(k))
              appends = appends.tail
            }
          } else appends = appends.tail

        }

        for (k <- 0 until nj.length) yield {
          val fCF = omega(nj(k), appends)

          if (fCF >= maxfcf) {
            if (fCF == maxfcf) {
              maxfcf = fCF
              nj2 = nj2.::(nj(k))
            } else {
              maxfcf = fCF
              nj2 = List(nj(k))
            }

          }

        }

        appends = appends.union(nj2)
        if (appends == compare) return appends
        val nAppends = neighborsOfList(appends)
        if (nAppends.size == 0) return appends
        if (fX(appends) < maxFf) { return appends.diff(nj2) }

        compare = appends

        nonHeuristicsCluster(appends, compare)

      }

      /*
	 * Input for heuristics heuristicsCluster(element)    .
	 * Input for nonHeuristics nonHeuristicsCluster(element,List())  . 
	 */

      def makeClusters(a: Long, b: Int): List[Long] = {

        var clusters: List[Long] = List()
        if (b == 0) {
          clusters = nonHeuristicsCluster(List(a), List())
        }
        if (b == 1) {
          clusters = heuristicsCluster(List(a))
        }

        (clusters)

      }

      var bigList: List[List[Long]] = List()

      for (i <- 0 until X.length) {

        val finalClusters = makeClusters(X(i), g)

        bigList = bigList.::(finalClusters)

      }

      bigList = bigList.map(_.distinct)

      def subset(c: List[List[Long]]): List[List[Long]] = {
        var C = c

        for (i <- 0 until c.length) {

          var counter = 0
          for (j <- i + 1 until c.length) {

            val cii = c(i)
            val cj = c(j)

            if ((c(i).diff(c(j))).size == 0 && (c(j).diff(c(i))).size == 0) {
              counter = counter + 1
              if (counter > 1) {
                val ci = c(j)
                C = C.diff(List(cj))

              }
            }
            if ((c(i).diff(c(j))).size == 0 && (c(j).diff(c(i))).size != 0) {
              C = C.diff(List(c(i)))

            }
            if ((c(i).diff(c(j))).size != 0 && (c(j).diff(c(i))).size == 0) {
              C = C.diff(List(c(j)))
            }
          }

        }
        C
      }

      bigList = subset(bigList)

      bigList = subset(bigList).sortBy(_.length).reverse

      def takeAllElements(c: List[List[Long]], x: List[Long]): List[List[Long]] = {

        var Cl: List[Long] = List()
        var cluster: List[List[Long]] = List()
        val y = (x.diff(Cl))

        for (i <- 0 until c.length) {

          if ((x.diff(Cl)).size != 0) {
            Cl = Cl.union(c(i))
            cluster = cluster.::(c(i))
          }
        }
        cluster
      }

      bigList = takeAllElements(bigList, X)

      def omegaCluster(v: Long, c: List[Long]): Double = {

        var omega = 0.0

        for (i <- 0 until c.length) yield {
          if (c(i) != v) {

            omega = omega.+(findingSimilarity(v, c(i)).abs)

          }

        }
        /* 
         * without similarity
         val nu = neighborSort.lookup(u).distinct.head.toSet
         val nuX = nu.intersect(X.toSet).toList
        ( (nuX.intersect(listOfN(x))).size.toDouble)
         
         */
        omega

      }

      def reassignment(c: List[List[Long]], x: List[Long]): List[List[Long]] = {
        var C = c
        for (i <- 0 until x.length) {
          var f = 0.0
          var nj: List[Long] = List()
          for (j <- 0 until C.length) {
            val om = omegaCluster(x(i), C(j))
            if (om > f) {
              f = om
              nj = C(j)
            }

          }
          C = C.diff(List(nj))
          var di: List[List[Long]] = List()
          for (k <- 0 until C.length) {
            val t = C(k).diff(List(x(i)))
            di = di.::(t)
          }

          C = di
          val cj = nj.::(x(i)).distinct
          C = C.::(cj)

        }
        C
      }
      def nul(c: List[List[Long]]): List[List[Long]] = {
        var C = c
        var newCluster: List[List[Long]] = List()
        for (k <- 0 until C.length) {
          if (C(k).size != 0) {
            newCluster = newCluster.::(C(k))
          }
        }
        newCluster
      }

      bigList = reassignment(bigList, X)
      bigList = nul(bigList)

      println(s"List of Cluster assignments after Hardening: $bigList\n")

      bigList
    }
    def makerdf(a: List[Long]): List[String] = {
      var listuri: List[String] = List()
      val b: List[VertexId] = a
      for (i <- 0 until b.length) {
        vertices.collect().map(v => {
          if (b(i) == v._1) listuri = listuri.::(v._2)
        })

      }
      listuri

    }
    val file = "BorderFlow1.txt"

    val pw = new PrintWriter(new File(file))
    val rdf = clusterRdd.map(x => makerdf(x))
    println(s"RDF Cluster assignments: $rdf\n")
    pw.println(s"RDF Cluster assignments: $rdf\n")
    pw.close()
  }
}
