package mhtml

import org.scalajs.dom
import org.scalajs.dom.raw.{Node => DomNode}
import scala.scalajs.js
import scala.xml.{Node => XmlNode, _}

object mount {
  /** Side-effectly mounts an `xml.Node` to a `org.scalajs.dom.raw.Node`. */
  def apply(parent: DomNode, child: XmlNode): Unit =
    { mountNode(parent, child, None); () }

  /** Side-effectly mounts an `Rx[xml.Node]` to a `org.scalajs.dom.raw.Node`. */
  def apply(parent: DomNode, obs: Rx[XmlNode]): Unit =
    { mountNode(parent, new Atom(obs), None); () }

  private def mountNode(parent: DomNode, child: XmlNode, startPoint: Option[DomNode]): Cancelable =
    child match {
      case a: Atom[_] if a.data.isInstanceOf[Rx[_]] =>
        val (start, end) = parent.createMountSection()
        var cancelable = Cancelable.empty
        a.data.asInstanceOf[Rx[_]].foreach { v =>
          parent.cleanMountSection(start, end)
          cancelable.cancel
          cancelable = v match {
            case n: XmlNode  =>
              mountNode(parent, n, Some(start))
            case seq: Seq[_] =>
              val nodeSeq = seq.map {
                case n: XmlNode => n
                case a => new Atom(a)
              }
              mountNode(parent, new Group(nodeSeq), Some(start))
            case a =>
              mountNode(parent, new Atom(a), Some(start))
          }
        } alsoCanceling (() => cancelable)

      case e @ Elem(_, label, metadata, _, child @ _*) =>
        val elemNode = dom.document.createElement(label)
        val cancelMetadata = metadata.map { m => mountMetadata(elemNode, m, m.value.asInstanceOf[Atom[_]].data) }
        val cancelChild = child.map(c => mountNode(elemNode, c, None))
        parent.mountHere(elemNode, startPoint)
        Cancelable { () => cancelMetadata.foreach(_.cancel()); cancelChild.foreach(_.cancel()) }

      case e: EntityRef  =>
        val key    = e.entityName
        val string = EntityRefMap(key)
        parent.mountHere(dom.document.createTextNode(string), startPoint)
        Cancelable.empty

      case a: Atom[_]    =>
        val content = a.data.toString
        if (!content.isEmpty)
          parent.mountHere(dom.document.createTextNode(content), startPoint)
        Cancelable.empty

      case Comment(text) =>
        parent.mountHere(dom.document.createComment(text), startPoint)
        Cancelable.empty

      case Group(nodes)  =>
        val cancels = nodes.map(n => mountNode(parent, n, startPoint))
        Cancelable(() => cancels.foreach(_.cancel))
    }

  private def mountMetadata(parent: DomNode, m: MetaData, v: Any): Cancelable = v match {
    case r: Rx[_] =>
      val rx: Rx[_] = r
      var cancelable = Cancelable.empty
      rx.foreach { value =>
        cancelable.cancel
        cancelable = mountMetadata(parent, m, value)
      } alsoCanceling (() => cancelable)
    case f: Function0[Unit @ unchecked] =>
      parent.setEventListener(m.key, (_: dom.Event) => f())
    case f: Function1[_, Unit @ unchecked] =>
      parent.setEventListener(m.key, f)
    case _ =>
      parent.setMetadata(m, v)
      Cancelable.empty
  }

  private implicit class DomNodeExtra(node: DomNode) {
    def setEventListener[A](key: String, listener: A => Unit): Cancelable = {
      val dyn = node.asInstanceOf[js.Dynamic]
      dyn.updateDynamic(key)(listener)
      Cancelable(() => dyn.updateDynamic(key)(null))
    }

    def setMetadata(m: MetaData, v: Any): Unit = {
      val htmlNode = node.asInstanceOf[dom.html.Html]
      def set(k: String): Unit =
        if (v == null) htmlNode.removeAttribute(k)
        else {
          val str = v.toString
          if (k == "style") htmlNode.style.cssText = str
          else htmlNode.setAttribute(k, str)
        }
      m match {
        case m: PrefixedAttribute => set(s"${m.pre}:${m.key}")
        case _ => set(m.key)
      }
    }

    // Creats and inserts two empty text nodes into the DOM, which delimitate
    // a mounting region between them point. Because the DOM API only exposes
    // `.insertBefore` things are reversed: at the position of the `}`
    // character in our binding example, we insert the start point, and at `{`
    // goes the end.
    def createMountSection(): (DomNode, DomNode) = {
      val start = dom.document.createTextNode("")
      val end   = dom.document.createTextNode("")
      node.appendChild(end)
      node.appendChild(start)
      (start, end)
    }

    // Elements are then "inserted before" the start point, such that
    // inserting List(a, b) looks as follows: `}` → `a}` → `ab}`. Note that a
    // reference to the start point is sufficient here. */
    def mountHere(child: DomNode, start: Option[DomNode]): Unit =
      { start.fold(node.appendChild(child))(point => node.insertBefore(child, point)); () }

    // Cleaning stuff is equally simple, `cleanMountSection` takes a references
    // to start and end point, and (tail recursively) deletes nodes at the
    // left of the start point until it reaches end of the mounting section. */
    def cleanMountSection(start: DomNode, end: DomNode): Unit = {
      val next = start.previousSibling
      if (next != end) {
        node.removeChild(next)
        cleanMountSection(start, end)
      }
    }
  }
}