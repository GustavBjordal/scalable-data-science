// Databricks notebook source exported at Wed, 14 Sep 2016 08:21:10 UTC
// MAGIC %md
// MAGIC # Analysis of ISIS Tweets Data 
// MAGIC 
// MAGIC ### 2016, Raazesh Sainudiin and Rania Sahioun

// COMMAND ----------

// MAGIC %md
// MAGIC This data set is from Khuram of Fifth Tribe.
// MAGIC 
// MAGIC # How ISIS Uses Twitter
// MAGIC ## Analyze how ISIS fanboys have been using Twitter since 2015 Paris Attacks
// MAGIC ### by Khuram
// MAGIC 
// MAGIC [Released Under CC0: Public Domain License](https://creativecommons.org/publicdomain/zero/1.0/)
// MAGIC 
// MAGIC This is from: https://www.kaggle.com/kzaman/how-isis-uses-twitter
// MAGIC 
// MAGIC ### Description
// MAGIC 
// MAGIC We scraped over 17,000 tweets from 100+ pro-ISIS fanboys from all over the world since the November 2015 Paris Attacks. We are working with content producers and influencers to develop effective counter-messaging measures against violent extremists at home and abroad. In order to maximize our impact, we need assistance in quickly analyzing message frames.
// MAGIC 
// MAGIC The dataset includes the following:
// MAGIC 
// MAGIC     Name
// MAGIC     Username
// MAGIC     Description
// MAGIC     Location
// MAGIC     Number of followers at the time the tweet was downloaded
// MAGIC     Number of statuses by the user when the tweet was downloaded
// MAGIC     Date and timestamp of the tweet
// MAGIC     The tweet itself
// MAGIC 
// MAGIC Based on this data, here are some useful ways of deriving insights and analysis:
// MAGIC 
// MAGIC     Social Network Cluster Analysis: Who are the major players in the pro-ISIS twitter network? Ideally, we would like this visualized via a cluster network with the biggest influencers scaled larger than smaller influencers.
// MAGIC     Keyword Analysis: Which keywords derived from the name, username, description, location, and tweets were the most commonly used by ISIS fanboys? Examples include: "baqiyah", "dabiq", "wilayat", "amaq"
// MAGIC     Data Categorization of Links: Which websites are pro-ISIS fanboys linking to? Categories include: Mainstream Media, Altermedia, Jihadist Websites, Image Upload, Video Upload,
// MAGIC     Sentiment Analysis: Which clergy do pro-ISIS fanboys quote the most and which ones do they hate the most? Search the tweets for names of prominent clergy and classify the tweet as positive, negative, or neutral and if negative, include the reasons why. Examples of clergy they like the most: "Anwar Awlaki", "Ahmad Jibril", "Ibn Taymiyyah", "Abdul Wahhab". Examples of clergy that they hate the most: "Hamza Yusuf", "Suhaib Webb", "Yaser Qadhi", "Nouman Ali Khan", "Yaqoubi".
// MAGIC     Timeline View: Visualize all the tweets over a timeline and identify peak moments
// MAGIC 
// MAGIC Further Reading: "ISIS Has a Twitter Strategy and It is Terrifying [Infographic]"
// MAGIC 
// MAGIC ### About Fifth Tribe
// MAGIC 
// MAGIC Fifth Tribe is a digital agency based out of DC that serves businesses, non-profits, and government agencies. We provide our clients with product development, branding, web/mobile development, and digital marketing services. Our client list includes Oxfam, Ernst and Young, Kaiser Permanente, Aetna Innovation Health, the U.S. Air Force, and the U.S. Peace Corps. Along with Goldman Sachs International and IBM, we serve on the Private Sector Committee of the Board of the Global Community Engagement and Resilience Fund (GCERF), the first global effort to support local, community-level initiatives aimed at strengthening resilience against violent extremism. In December 2014, we won the anti-ISIS "Hedaya Hack" organized by Affinis Labs and hosted at the "Global Countering Violent Extremism (CVE) Expo " in Abu Dhabi. Since then, we've been actively involved in working with the open-source community and community content producers in developing counter-messaging campaigns and tools.

// COMMAND ----------

// MAGIC %md
// MAGIC The data has been written to parquet file. So let's just read it into a DataFrame (See **Writing Isis Tweets to a Parquet File** below to do this step in a new shard).

// COMMAND ----------

1+1

// COMMAND ----------

val tweetsIsisRawDF = sqlContext.read.parquet("/datasets/tweetsIsisRaw")
tweetsIsisRawDF.count()

// COMMAND ----------

display(tweetsIsisRawDF)

// COMMAND ----------

// MAGIC %md
// MAGIC ## Mentions Network

// COMMAND ----------

import sqlContext.implicits._
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._

//import java.sql.Timestamp

// COMMAND ----------

//make a DataFrame of mentions ie a mentions network with edges given by {(username, mentioned_usernames)}
val mentionsNetworkDF = tweetsIsisRawDF
                          .select($"username",$"tweets",$"time")
                          .filter($"tweets" rlike ".*@.*")
                          .explode($"tweets")( 
                                                 _.getAs[String](0).split(" ")
                                                .filter(a => a.matches("^@.*"))
                                                .map(a => a.replace("@",""))
                                                .map(a => a.replace(":",""))
                                                .map(Tuple1(_))
                                              )
                                              .select($"username",$"_1".as("mentions"),$"time",unix_timestamp($"time", "MM/dd/yyyy HH:mm").cast(TimestampType).as("timestamp"))
                                              .withColumn("weight", lit(1L))

// COMMAND ----------

display(mentionsNetworkDF)

// COMMAND ----------

// MAGIC %md
// MAGIC ### Top 10 most active usernames

// COMMAND ----------

display(mentionsNetworkDF
  .select(mentionsNetworkDF("username"), mentionsNetworkDF("weight"))
  .groupBy("username")
  .sum()
  .orderBy($"sum(weight)".desc)
  .limit(10))

// COMMAND ----------

// MAGIC %md
// MAGIC ### Top 10 most active (usernames,mentions) directed-edge pairs

// COMMAND ----------

display(mentionsNetworkDF
  .select(mentionsNetworkDF("username"), mentionsNetworkDF("mentions"), mentionsNetworkDF("weight"))
  .groupBy("username","mentions")
  .sum()
  .orderBy($"sum(weight)".desc)
  .limit(10))

// COMMAND ----------

// MAGIC %md
// MAGIC Full network of mentions with weights

// COMMAND ----------

val mentionsWeightedNetworkDF = mentionsNetworkDF
  .select(mentionsNetworkDF("username"), mentionsNetworkDF("mentions"), mentionsNetworkDF("weight"))
  .groupBy("username","mentions")
  .sum()
  .orderBy($"sum(weight)".desc)
  .cache()

// COMMAND ----------

display(mentionsWeightedNetworkDF)

// COMMAND ----------

mentionsWeightedNetworkDF.count()

// COMMAND ----------

// MAGIC %md
// MAGIC #### Load a visualization library
// MAGIC This code is copied after doing a live google search (by Michael Armbrust at Spark Summit East February 2016 
// MAGIC shared from [https://twitter.com/michaelarmbrust/status/699969850475737088](https://twitter.com/michaelarmbrust/status/699969850475737088)).

// COMMAND ----------

package d3
// We use a package object so that we can define top level classes like Edge that need to be used in other cells

import org.apache.spark.sql._
import com.databricks.backend.daemon.driver.EnhancedRDDFunctions.displayHTML

case class Edge(src: String, dest: String, count: Long)

case class Node(name: String)
case class Link(source: Int, target: Int, value: Long)
case class Graph(nodes: Seq[Node], links: Seq[Link])

object graphs {
val sqlContext = SQLContext.getOrCreate(org.apache.spark.SparkContext.getOrCreate())  
import sqlContext.implicits._
  
def force(clicks: Dataset[Edge], height: Int = 100, width: Int = 960): Unit = {
  val data = clicks.collect()
  val nodes = (data.map(_.src) ++ data.map(_.dest)).map(_.replaceAll("_", " ")).toSet.toSeq.map(Node)
  val links = data.map { t =>
    Link(nodes.indexWhere(_.name == t.src.replaceAll("_", " ")), nodes.indexWhere(_.name == t.dest.replaceAll("_", " ")), t.count / 20 + 1)
  }
  showGraph(height, width, Seq(Graph(nodes, links)).toDF().toJSON.first())
}

/**
 * Displays a force directed graph using d3
 * input: {"nodes": [{"name": "..."}], "links": [{"source": 1, "target": 2, "value": 0}]}
 */
def showGraph(height: Int, width: Int, graph: String): Unit = {

displayHTML(s"""
<!DOCTYPE html>
<html>
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
  <title>Polish Books Themes - an Interactive Map</title>
  <meta charset="utf-8">
<style>

.node_circle {
  stroke: #777;
  stroke-width: 1.3px;
}

.node_label {
  pointer-events: none;
}

.link {
  stroke: #777;
  stroke-opacity: .2;
}

.node_count {
  stroke: #777;
  stroke-width: 1.0px;
  fill: #999;
}

text.legend {
  font-family: Verdana;
  font-size: 13px;
  fill: #000;
}

.node text {
  font-family: "Helvetica Neue","Helvetica","Arial",sans-serif;
  font-size: 17px;
  font-weight: 200;
}

</style>
</head>

<body>
<script src="//d3js.org/d3.v3.min.js"></script>
<script>

var graph = $graph;

var width = $width,
    height = $height;

var color = d3.scale.category20();

var force = d3.layout.force()
    .charge(-700)
    .linkDistance(180)
    .size([width, height]);

var svg = d3.select("body").append("svg")
    .attr("width", width)
    .attr("height", height);
    
force
    .nodes(graph.nodes)
    .links(graph.links)
    .start();

var link = svg.selectAll(".link")
    .data(graph.links)
    .enter().append("line")
    .attr("class", "link")
    .style("stroke-width", function(d) { return Math.sqrt(d.value); });

var node = svg.selectAll(".node")
    .data(graph.nodes)
    .enter().append("g")
    .attr("class", "node")
    .call(force.drag);

node.append("circle")
    .attr("r", 10)
    .style("fill", function (d) {
    if (d.name.startsWith("other")) { return color(1); } else { return color(2); };
})

node.append("text")
      .attr("dx", 10)
      .attr("dy", ".35em")
      .text(function(d) { return d.name });
      
//Now we are giving the SVGs co-ordinates - the force layout is generating the co-ordinates which this code is using to update the attributes of the SVG elements
force.on("tick", function () {
    link.attr("x1", function (d) {
        return d.source.x;
    })
        .attr("y1", function (d) {
        return d.source.y;
    })
        .attr("x2", function (d) {
        return d.target.x;
    })
        .attr("y2", function (d) {
        return d.target.y;
    });
    d3.selectAll("circle").attr("cx", function (d) {
        return d.x;
    })
        .attr("cy", function (d) {
        return d.y;
    });
    d3.selectAll("text").attr("x", function (d) {
        return d.x;
    })
        .attr("y", function (d) {
        return d.y;
    });
});
</script>
</html>
""")
}
  
  def help() = {
displayHTML("""
<p>
Produces a force-directed graph given a collection of edges of the following form:</br>
<tt><font color="#a71d5d">case class</font> <font color="#795da3">Edge</font>(<font color="#ed6a43">src</font>: <font color="#a71d5d">String</font>, <font color="#ed6a43">dest</font>: <font color="#a71d5d">String</font>, <font color="#ed6a43">count</font>: <font color="#a71d5d">Long</font>)</tt>
</p>
<p>Usage:<br/>
<tt><font color="#a71d5d">import</font> <font color="#ed6a43">d3._</font></tt><br/>
<tt><font color="#795da3">graphs.force</font>(</br>
&nbsp;&nbsp;<font color="#ed6a43">height</font> = <font color="#795da3">500</font>,<br/>
&nbsp;&nbsp;<font color="#ed6a43">width</font> = <font color="#795da3">500</font>,<br/>
&nbsp;&nbsp;<font color="#ed6a43">clicks</font>: <font color="#795da3">Dataset</font>[<font color="#795da3">Edge</font>])</tt>
</p>""")
  }
}

// COMMAND ----------

d3.graphs.help()

// COMMAND ----------

// MAGIC %md
// MAGIC #### The weighted mentions network with top 20 most heavy directed edges

// COMMAND ----------

  val clicksDS = mentionsWeightedNetworkDF
                              .select($"username".as("src"), $"mentions".as("dest"), $"sum(weight)".as("count"))
                              .orderBy($"count".desc)
                              .limit(20)
                              .as[d3.Edge]

// COMMAND ----------

clicksDS.show()

// COMMAND ----------

d3.graphs.force( // self-loops are ignored
  height = 800,
  width = 1000,
  clicks = clicksDS
)

// COMMAND ----------

// MAGIC %md
// MAGIC ### GraphFrame of Mentions Network

// COMMAND ----------

// MAGIC %md
// MAGIC Turn the DataFrame into GraphFrame for more exploration.

// COMMAND ----------

// MAGIC %md
// MAGIC ***
// MAGIC ***

// COMMAND ----------

// MAGIC %md
// MAGIC ## Writing Isis Tweets to a Parquet File

// COMMAND ----------

sqlContext.tables.show() // let us view the tables - the data was uploaded as csv file into databricks table

// COMMAND ----------

val tweetIsisDF = sqlContext.table("tweetisis") // converting table into DataFrame

// COMMAND ----------

tweetIsisDF.cache() // caching the DataFrame

// COMMAND ----------

tweetIsisDF.count() // couting the rows

// COMMAND ----------

tweetIsisDF.count() // counting again after chache

// COMMAND ----------

display(tweetIsisDF)

// COMMAND ----------

tweetIsisDF.printSchema()

// COMMAND ----------

// Convert the DatFrame to a more efficent format to speed up our analysis
tweetIsisDF.
  write.
  mode(SaveMode.Overwrite).
  parquet("/datasets/tweetsIsisRaw") // warnings are harmless

// COMMAND ----------

import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._
//we will convert price column from int to double later
val tweetsIsisDF = tweetIsisDF.select($"name", $"username", $"description", $"location", $"followers".cast(LongType), $"numberstatuses".cast(LongType),$"time",unix_timestamp($"time", "MM/dd/yyyy HH:mm").cast(TimestampType).as("timestamp"), $"tweets")
tweetsIsisDF.cache() // let's cache it for reuse
tweetsIsisDF.printSchema // print schema

// COMMAND ----------

// Convert the DatFrame to a more efficent format to speed up our analysis
tweetsIsisDF.
  write.
  mode(SaveMode.Overwrite).
  parquet("/datasets/tweetsIsis") // warnings are harmless

// COMMAND ----------

display(dbutils.fs.ls("/datasets"))

// COMMAND ----------

val tweetsIsisDF = sqlContext.read.parquet("/datasets/tweetsIsis")
tweetsIsisDF.printSchema

// COMMAND ----------

val tweetsIsisRawDF = sqlContext.read.parquet("/datasets/tweetsIsisRaw")
tweetsIsisRawDF.printSchema

// COMMAND ----------

