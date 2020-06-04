package viper.gobra.frontend.info.implementation.typing.ghost.separation

import viper.gobra.ast.frontend._
import viper.gobra.frontend.info.implementation.property.{AssignMode, StrictAssignModi}
import viper.gobra.util.Violation
import viper.gobra.frontend.info.implementation.TypeInfoImpl

import org.bitbucket.inkytonik.kiama.attribution.Decorators



/**
  * Whole spec of a function or method which needs goification.
  */
case class DeclarationSpec(ghostParams: Vector[PExplicitGhostParameter], ghostResultParams: Vector[PExplicitGhostParameter], spec: PFunctionSpec)





class GoifyingPrinter(info: TypeInfoImpl) extends DefaultPrettyPrinter {

  val classifier: GhostClassifier = info.asInstanceOf[GhostClassifier]

  /**
    * Used to determine if current expression is already in Goified scope.
    */
  val decorators = new Decorators(info.tree)
  lazy val isNotInGoifiedScope: PNode => Boolean =
    decorators.down(true){
      case _: PFPredicateDecl | _: PMPredicateDecl | _: PFunctionSpec | _: PGhostStatement | _: PUnfolding  => false
    }

  lazy val unfoldingNotInGoifiedScope: PUnfolding => Boolean =
    decorators.down(true){
      case _: PFPredicateDecl | _: PMPredicateDecl | _: PFunctionSpec | _: PGhostStatement => false
    }

  /*
  lazy val isEnclosedInGoifiedScope: PNode => Boolean =
    decorators.down(false){
      case _: PFPredicateDecl | _: PMPredicateDecl | _: PFunctionSpec | _: PGhostStatement => true
    }
  */

  /**
    * Keywords used in Goified files.
    */
  private val ghost_parameters: String = "ghost-parameters:"
  private val ghost_results: String = "ghost-results:"
  private val addressable_variables: String = "addressable:"
  private val predicate_access: String = "predicate-access:"
  private val with_keyword: String = "with:"
  private val unfolding_keyword: String = "unfolding:"

  private val specComment: Doc = "//@"
  private def blockSpecComment(doc: Doc): Doc = "/*@" <> line <> doc <> line <> "@*/"
  private def inlinedSpecComment(doc: Doc): Doc = "/*@" <+> doc <+> "@*/"

  private def with_prefix[T](vec: Vector[T]): Doc = if (vec.isEmpty) emptyDoc else space <> with_keyword


  /**
    * Helper methods to get the parameters and results filtered (without ghost)
    * and also only ghost
    */
  private def filterParamList[T <: PParameter](paras: Vector[T]): Vector[T] =
    paras.filter(!classifier.isParamGhost(_))

  private def ghostParamList[T <: PParameter](paras: Vector[T]): Vector[PExplicitGhostParameter] =
    paras.filter(classifier.isParamGhost(_)).asInstanceOf[Vector[PExplicitGhostParameter]]

  private def filterResult(res: PResult): PResult = {
    val aOuts = res.outs.filter(!classifier.isParamGhost(_))
    PResult(aOuts)
  }


  /**
    * Shows the Goified version of the function / method specification
    */
  override def showSpec(spec: PSpecification): Doc = spec match {
    case PFunctionSpec(pres, posts, isPure) =>
      (if (isPure) specComment <+> showPure else emptyDoc) <>
      hcat(pres map (p => specComment <+> showPre(p) <> line)) <>
      hcat(posts map (p => specComment <+> showPost(p) <> line))

    case PLoopSpec(inv) =>
      hcat(inv map (p => specComment <+> showInv(p) <> line))
  }

  /**
    * Shows the ghost parameters, the ghost results and the Goified version
    * of the function / method specification.
    */
  def showDeclarationSpec(spec: DeclarationSpec): Doc = spec match {
    case DeclarationSpec(ghostParams, ghostResultParams, spec) =>
      (if (ghostParams.isEmpty) emptyDoc else specComment <+> ghost_parameters <+> showParameterList(ghostParams.map(_.actual)) <> line) <>
      (if (ghostResultParams.isEmpty) emptyDoc else specComment <+> ghost_results <+> showParameterList(ghostResultParams.map(_.actual)) <> line) <>
      showSpec(spec)
  }

  /**
    * Shows a list of addressable variables.
    */
  def showAddressableVars(vars: Vector[PIdnUnk], prefix: Doc): Doc =
      (if (vars.isEmpty) emptyDoc else prefix <+> addressable_variables <+> showList(vars)(showId(_)))


  /**
    * Shows the Goified version of a program member.
    */
  override def showMember(mem: PMember): Doc = mem match {

    case PMethodDecl(id, rec, args, res, spec, body) =>
      showDeclarationSpec(DeclarationSpec(ghostParamList(args), ghostParamList(res.outs), spec)) <>
      super.showMember(PMethodDecl(id, rec, filterParamList(args), filterResult(res), PFunctionSpec(Vector.empty, Vector.empty), body))

    case PFunctionDecl(id, args, res, spec, body) =>
      showDeclarationSpec(DeclarationSpec(ghostParamList(args), ghostParamList(res.outs), spec)) <>
      super.showMember(PFunctionDecl(id, filterParamList(args), filterResult(res), PFunctionSpec(Vector.empty, Vector.empty), body))

    case pred: PFPredicateDecl => blockSpecComment(super.showMember(pred))

    case pred: PMPredicateDecl => blockSpecComment(super.showMember(pred))

    case m if classifier.isMemberGhost(m) => specComment <+> super.showMember(m)
    case m => super.showMember(m)
  }


  /**
    * Shows a ghost statement in the goified version with a given prefix.
    */
  def showGhostStmt(stmt: PStatement, prefix: Doc): Doc = specComment <> prefix <+> super.showStmt(stmt)

  override def showStmt(stmt: PStatement): Doc = stmt match {

    

    //case s if isEnclosedInGoifiedScope(s) => super.showStmt(s)

    case PForStmt(pre, cond, post, _, body) =>
      super.showStmt(PForStmt(pre, cond, post, PLoopSpec(Vector.empty), body))

    case PAssignment(right, left) =>
      StrictAssignModi(left.size, right.size) match {
        case AssignMode.Single =>
          val (aRight, aLeft) = right.zip(left).filter(p => !classifier.isExprGhost(p._2)).unzip
          val (ghostRight, ghostLeft) = right.zip(left).filter(p => classifier.isExprGhost(p._2)).unzip

          (if (aLeft.isEmpty) emptyDoc else super.showStmt(PAssignment(aRight, aLeft))) <>
          (if (ghostLeft.isEmpty) emptyDoc else showGhostStmt(PAssignment(ghostRight, ghostLeft), with_prefix(aLeft)))
          
        case AssignMode.Multi =>
          val aLeft = left.filter(!classifier.isExprGhost(_))
          val ghostLeft = left.filter(classifier.isExprGhost(_))

          (if (aLeft.isEmpty) emptyDoc else super.showStmt(PAssignment(right, aLeft))) <>
          (if (ghostLeft.isEmpty) emptyDoc else showGhostStmt(PAssignment(right, ghostLeft), with_prefix(aLeft)))

        case AssignMode.Error => errorMsg
      }

    case PShortVarDecl(right, left, addressable) =>
      StrictAssignModi(left.size, right.size) match {
        case AssignMode.Single =>
          val (aRight, aLeft) = right.zip(left).filter(p => !classifier.isIdGhost(p._2)).unzip
          // List of all non-ghost addressable variables.
          val aAddressableVars = left.zip(addressable).filter(p => !classifier.isIdGhost(p._1) && p._2).map(_._1)
          val (ghostRight, ghostLeft) = right.zip(left).filter(p => classifier.isIdGhost(p._2)).unzip
          // Boolean vector of whether ghost variables are addressable or not.
          val ghostAddressable = left.zip(addressable).filter(p => classifier.isIdGhost(p._1)).map(_._2)

          val prefix: Doc = if (ghostLeft.isEmpty) specComment else ";"

          (if (aLeft.isEmpty) emptyDoc else super.showStmt(PShortVarDecl(aRight, aLeft, aLeft.map(_ => false)))) <>
          (if (ghostLeft.isEmpty) emptyDoc else showGhostStmt(PShortVarDecl(ghostRight, ghostLeft, ghostAddressable), with_prefix(aLeft))) <+>
          showAddressableVars(aAddressableVars, prefix)

        case AssignMode.Multi =>
          val aLeft = left.filter(!classifier.isIdGhost(_))
          // List of all non-ghost addressable variables.
          val aAddressableVars = left.zip(addressable).filter(p => !classifier.isIdGhost(p._1)).map(_._1)
          val ghostLeft = left.filter(classifier.isIdGhost(_))
          // Boolean vector of whether ghost variables are addressable or not.
          val ghostAddressable = left.zip(addressable).filter(p => classifier.isIdGhost(p._1)).map(_._2)

          val prefix: Doc = if (ghostLeft.isEmpty) specComment else ";"
          
          (if (aLeft.isEmpty) emptyDoc else super.showStmt(PShortVarDecl(right, aLeft, aLeft.map(_ => false)))) <>
          (if (ghostLeft.isEmpty) emptyDoc else showGhostStmt(PShortVarDecl(right, ghostLeft, ghostAddressable), with_prefix(aLeft))) <+>
          showAddressableVars(aAddressableVars, prefix)

        case AssignMode.Error => errorMsg
      }

    case n@ PReturn(right) =>
      val gt = classifier.expectedReturnGhostTyping(n)
      val aRight = right.zip(gt.toTuple).filter(!_._2).map(_._1)
      val ghostRight = right.zip(gt.toTuple).filter(_._2).map(_._1)
      
      (if (aRight.isEmpty) emptyDoc else super.showStmt(PReturn(aRight))) <>
      (if (ghostRight.isEmpty) emptyDoc else showGhostStmt(PReturn(ghostRight), with_prefix(aRight)))

    case s if isNotInGoifiedScope(s) => super.showStmt(s)
    case s if classifier.isStmtGhost(s) => showGhostStmt(stmt, emptyDoc)

    case s => super.showStmt(stmt)

  }


  override def showExpr(expr: PExpression): Doc = expr match {
    
    case n: PInvoke if isNotInGoifiedScope(n) =>
      val gt = classifier.expectedArgGhostTyping(n)
      val aArgs = n.args.zip(gt.toTuple).filter(!_._2).map(_._1)
      val ghostArgs = n.args.zip(gt.toTuple).filter(_._2).map(_._1)

      super.showExpr(n.copy(args = aArgs)) <> (if (ghostArgs.isEmpty) emptyDoc else space <> inlinedSpecComment(with_keyword <+> showExprList(ghostArgs)))

    
    case e: PUnfolding if unfoldingNotInGoifiedScope(e) =>
      //parens(showExpr(e.op) <+> inlinedSpecComment(unfolding_keyword <+> super.showExpr(e.pred)))
      parens(inlinedSpecComment(unfolding_keyword <+> super.showExpr(e.pred)) <+> showExpr(e.op))

    case e: PUnfolding => parens(super.showExpr(e))

    case e: PActualExprProofAnnotation => showExpr(e.op)
    case e => super.showExpr(e)
  }



  /**
    * Shows ghost types in the goified version.
    */
  def showGhostType(typ: PType): Doc = specComment <+> super.showType(typ)

  override def showType(typ: PType): Doc = typ match {

    //case t if isEnclosedInGoifiedScope(t) => super.showType(t)

    case PStructType(clauses) =>
      val ghostClauses = ghostStructClauses(clauses)

      super.showType(PStructType(filterStructClauses(clauses))) <+>
      (if (ghostClauses.isEmpty) emptyDoc else showGhostType(PStructType(ghostClauses)))

    case PInterfaceType(embedded, mspecs, pspecs) =>
      val ghostEmbedded = ghostInterfaceClause(embedded)
      val ghostMspecs = ghostInterfaceClause(mspecs)
      val ghostPspecs = ghostInterfaceClause(pspecs)

      "interface" <+> block(
        ssep(filterInterfaceClause(embedded) map showInterfaceClause, line) <>
        (if (ghostEmbedded.isEmpty) emptyDoc else ssep(ghostEmbedded map (specComment <+> showInterfaceClause(_)), line)) <>

        ssep(filterInterfaceClause(mspecs) map showInterfaceClause, line) <>
        (if (ghostMspecs.isEmpty) emptyDoc else ssep(ghostMspecs map (specComment <+> showInterfaceClause(_)), line)) <>

        ssep(filterInterfaceClause(pspecs) map showInterfaceClause, line) <>
        (if (ghostPspecs.isEmpty) emptyDoc else ssep(ghostPspecs map (specComment <+> showInterfaceClause(_)), line))
      )

    case t => super.showType(t)
  }

  private def filterStructClauses[T <: PStructClause](cl: Vector[T]): Vector[T] =
    cl.filter(!classifier.isStructClauseGhost(_))

  private def ghostStructClauses[T <: PStructClause](cl: Vector[T]): Vector[T] =
    cl.filter(classifier.isStructClauseGhost(_))

  private def filterInterfaceClause[T <: PInterfaceClause](cl: Vector[T]): Vector[T] =
    cl.filter(!classifier.isInterfaceClauseGhost(_))

  private def ghostInterfaceClause[T <: PInterfaceClause](cl: Vector[T]): Vector[T] =
    cl.filter(classifier.isInterfaceClauseGhost(_))


  private def errorMsg: Nothing = Violation.violation("GoifyingPrinter has to be run after the type check")
}


