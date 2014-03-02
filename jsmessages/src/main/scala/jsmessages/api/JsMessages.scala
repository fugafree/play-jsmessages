package jsmessages.api

import play.api.i18n._
import play.api.Application
import org.apache.commons.lang3.StringEscapeUtils.escapeEcmaScript
import play.api.templates.JavaScript

/**
 * Can generate a JavaScript function computing localized messages of a Play application
 * @param app Play application to use
 */
class JsMessages(implicit app: Application) {

  /**
   * All the messages of the application, as a map of (lang -> map(key -> message)).
   *
   * The default implementation returns the Play! application messages. Override this lazy val to supply additional messages.
   */
  lazy val allMessages: Map[String, Map[String, String]] = Messages.messages
  
  /**
   * The application’s messages to use by default, as a map of (key -> message).
   * Data is already ECMAScript-escaped.
   */
  private val defaultMessagesEscaped: Map[String, String] =
    escapeMap(Messages.messages.get("default.play").getOrElse(Map.empty)) ++
    escapeMap(Messages.messages.get("default").getOrElse(Map.empty))

  /**
   * The messages defined in the given Play application `app`, for all languages, as a map of (lang -> map(key -> message))
   * with addition of default messages and Play Framework messages.
   */
  lazy val allMessagesEscaped: Map[String, Map[String, String]] = Messages.messages.mapValues(escapeMap)

  /**
   *  Nearly JSON formated string of allMessages.
   */
  private val allMessagesString: String = formatAllMap(allMessagesEscaped)

  /**
   * Cache to memorize computed messages for each available lang
   * Computation consists of merging default messages, default Play messages,
   * messages for the language of the lang and messages of the lang itself.
   */
  private val messagesCache: Map[String, Map[String, String]] = for ((lang, msgs) <- allMessagesEscaped) yield {
    val maybeCountry = if (lang.contains("-")) Some(lang.split("-")(0)) else None

    lang -> (
      defaultMessagesEscaped ++
      maybeCountry.flatMap(country => allMessagesEscaped.get(country)).getOrElse(Map.empty) ++
      msgs
    )
  }

  /**
   *  Cache to memorize the nearly JSON formatted string of each lang messages.
   */
  private val messagesStringCache: Map[String, String] = messagesCache.mapValues(formatMap)

  /**
   * @param lang Language to retrieve messages for
   * @return The messages defined in the given Play application `app`, for the given language `lang`, as a map of (key -> message).
   */
  private def messages(implicit lang: Lang): Map[String, String] = messagesCache.get(lang.code).getOrElse(Map.empty)

  /**
   * @param lang Language to retrieve messages for
   * @return The nearly JSON formated string of messages defined in the given Play application `app`,
   * for the given language `lang`, as a map of (key -> message).
   */
  private def messagesString(implicit lang: Lang): String = messagesStringCache.get(lang.code).getOrElse("")

  /**
   * Generates a JavaScript function computing localized messages.
   *
   * For example:
   *
   * {{{
   *   def jsMessages = Action { implicit request =>
   *     Ok(jsMessages(Some("window.MyMessages"))).as(JAVASCRIPT)
   *   }
   * }}}
   *
   * Then use it in your JavaScript code as follows:
   *
   * {{{
   *   alert(MyMessages('greeting', 'World'));
   * }}}
   *
   * Provided you have the following message in your conf/messages file:
   *
   * {{{
   * greeting=Hello {0}!
   * }}}
   *
   * Note: This implementation does not handle quotes escaping in patterns (see http://docs.oracle.com/javase/7/docs/api/java/text/MessageFormat.html)
   *
   * @param namespace Optional JavaScript namespace to use to put the function definition. If not set this function will
   * just generate a function. Otherwise it will generate a function and assign it to the given namespace. Note: you can
   * set something like `Some("var Messages")` to use a fresh variable.
   */
  def apply(namespace: Option[String] = None)(implicit lang: Lang): JavaScript = apply(namespace, messagesString)

  /**
   * Generates a JavaScript function computing all messages.
   *
   * For example:
   *
   * {{{
   *   def jsMessages = Action { implicit request =>
   *     Ok(jsMessages.all(Some("window.MyMessages"))).as(JAVASCRIPT)
   *   }
   * }}}
   *
   * Then use it in your JavaScript code as follows:
   *
   * {{{
   *   alert(MyMessages('en', 'greeting', 'World'));
   * }}}
   *
   * Provided you have the following message in your conf/messages file:
   *
   * {{{
   * greeting=Hello {0}!
   * }}}
   *
   * Note: This implementation does not handle quotes escaping in patterns (see http://docs.oracle.com/javase/7/docs/api/java/text/MessageFormat.html)
   *
   * @param namespace Optional JavaScript namespace to use to put the function definition. If not set this function will
   * just generate a function. Otherwise it will generate a function and assign it to the given namespace. Note: you can
   * set something like `Some("var Messages")` to use a fresh variable.
   */
  def all(namespace: Option[String] = None): JavaScript = all(namespace, allMessagesString)

  /**
   * Generates a JavaScript function computing localized messages for a given keys subset.
   *
   * Example:
   *
   * {{{
   *   jsMessages.subset(Some("window.MyMessages"))(
   *     "error.required",
   *     "error.number"
   *   )
   * }}}
   */
  def subset(namespace: Option[String] = None)(keys: String*)(implicit lang: Lang): JavaScript =
     apply(namespace, formatMap(subsetMap(messages, keys: _*)))

  /**
   * Generates a JavaScript function computing all messages for a given keys subset.
   *
   * Example:
   *
   * {{{
   *   jsMessages.allSubset(Some("window.MyMessages"))(
   *     "error.required",
   *     "error.number"
   *   )
   * }}}
   */
  def allSubset(namespace: Option[String] = None)(keys: String*): JavaScript =
    all(namespace, formatAllMap(allMessagesEscaped.mapValues(m => subsetMap(m, keys: _*))))


  private def apply(namespace: Option[String], messages: String): JavaScript = {
    JavaScript(s""" #${namespace.map{_ + "="}.getOrElse("")}(function(u){function f(k){
          #var m;
          #if(typeof k==='object'){
            #for(var i=0,l=k.length;i<l&&f.messages[k[i]]===u;++i);
            #m=f.messages[k[i]]||k[0]
          #}else{
            #m=((f.messages[k]!==u)?f.messages[k]:k)
          #}
          #for(i=1;i<arguments.length;i++){
            #m=m.replace('{'+(i-1)+'}',arguments[i])
          #}
          #return m};
          #f.messages={$messages};
          #return f})()""".stripMargin('#'))
  }

  /**
   * @param namespace Optional JavaScript namespace to use to put the function definition. If not set, this function will
   *                  just return a literal function. Otherwise it will generate a function and assign it to the given namespace.
   *                  Note: you can set something like `Some("var Messages")` to use a fresh variable.
   * @param messages String correctly formatted as JSON corresponding the to Map of messages.
   * @return A JavaScript fragment defining a function computing all messages
   */
  private def all(namespace: Option[String], messages: String): JavaScript = {
    // g(key): given a lang, try to find a key among all possible messages,
    //              will try lang, lang.language, default and finally default.play
    // h(key,args...): return the formatted message retrieved from g(lang,key)
    // f(lang,key,args...): if only lang, return anonymous function always calling h by prefixing arguments with lang
    //                      else, just call h with current arguments
    JavaScript(s""" #${namespace.map{_ + "="}.getOrElse("")}(function(u){function f(l,k){
          #function g(k){
            #var r=f.messages[l][k];
            #if (r===u&&l.indexOf('-')>-1) {var lg=l.split('-')[0];r=f.messages[lg] && f.messages[lg][k];}
            #if (r===u) {r=f.messages['default'] && f.messages['default'][k];}
            #if (r===u) {r=f.messages['default.play'] && f.messages['default.play'][k];}
            #return r;
          #}
          #function h(k){
            #var m;
            #if(typeof k==='object'){
              #for(var i=0,le=k.length;i<le&&g(k[i])===u;++i);
              #m=g(k[i])||k[0];
            #}else{
              #m=g(k);
              #m=((m!==u)?m:k);
            #}
            #for(i=1,le=arguments.length;i<le;++i){
              #m=m.replace('{'+(i-1)+'}',arguments[i])
            #}
            #return m;
          #}
          #if(k===undefined){
            #return h;
          #}else{
            #return h.apply(u, Array.prototype.slice.call(arguments, 1));
          #}
        #}
        #f.messages={$messages};
        #return f})()""".stripMargin('#'))
  }

  private def subsetMap(values: Map[String, String], keys: String*): Map[String, String] =
    (for {
      key <- keys
      message <- values.get(key)
    } yield (key, message)).toMap

  // Escape all values of the map, using escapeEcmaScript
  // and replacing all doubled quotes by a single quote
  private def escapeMap(values: Map[String, String]): Map[String, String] =
    for ((key, value) <- values) yield escapeEcmaScript(key) -> escapeEcmaScript(value.replace("''", "'"))

  // Format a map to a nearly JSON string corresponding to a JavaScript object
  // only missing are the brackets around
  // Assumes that data is already ECMAScript-escaped.
  private def formatMap(values: Map[String, String]): String =
    (for ((key, msg) <- values) yield {
      "'%s':'%s'".format(key, msg)
    }).mkString(",")

  // Quite the same as 'formatMap' but for a Map[String, Map]
  // resulting in a Object(String -> Object)
  // still missing brackets at the beginning and at the end
  // Assumes that data is already ECMAScript-escaped.
  private def formatAllMap(values: Map[String, Map[String, String]]): String =
    (for ((lang, messages) <- values) yield {
      "'%s':{%s}".format(lang, formatMap(messages))
    }).mkString(",")
}
