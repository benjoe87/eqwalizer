/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.BinarySpecifiers
import com.whatsapp.eqwalizer.ast.Guards.TestAtom
import com.whatsapp.eqwalizer.ast.Pats._
import com.whatsapp.eqwalizer.ast.Types._
import com.whatsapp.eqwalizer.tc.TcDiagnostics.{UnboundRecord, UnhandledOp}

final class ElabPat(pipelineContext: PipelineContext) {
  private lazy val module = pipelineContext.module
  private lazy val narrow = pipelineContext.narrow
  private lazy val subtype = pipelineContext.subtype
  private lazy val util = pipelineContext.util
  private lazy val check = pipelineContext.check

  def elabPats(pats: List[Pat], tys: List[Type], env: Env): (List[Type], Env) = {
    var envAcc = env
    val patTypes = (pats zip tys).map { case (pat, ty) =>
      val (patType, env1) = elabPat(pat, ty, envAcc)
      envAcc = env1
      patType
    }
    (patTypes, envAcc)
  }

  def elabPat(pat: Pat, t: Type, env: Env): (Type, Env) = {
    pat match {
      case PatWild() =>
        (t, env)
      case PatVar(v) =>
        val patType = env.get(v) match {
          case Some(vt) => narrow.meet(t, vt)
          case None     => t
        }
        (patType, env + (v -> patType))
      case PatAtom(s) =>
        val patType = narrow.meet(t, AtomLitType(s))
        (patType, env)
      case PatInt() =>
        val patType = narrow.meet(t, NumberType)
        (patType, env)
      case PatNumber() =>
        val patType = narrow.meet(t, NumberType)
        (patType, env)
      case PatString() =>
        val patType = narrow.meet(t, stringType)
        (patType, env)
      case PatTuple(elems) =>
        val arity = elems.size
        val parts = narrow.asTupleType(t, arity)
        val restrictingParts =
          if (parts.isEmpty) List(TupleType(List.fill(arity)(NoneType)))
          else parts
        val tyEnvPairs =
          for (TupleType(elemTypes) <- restrictingParts) yield {
            var envAcc = env
            val patTypes = elems.lazyZip(elemTypes).map { (elem, elemT) =>
              val (patType, env1) = elabPat(elem, elemT, envAcc)
              envAcc = env1
              patType
            }
            if (patTypes.exists(subtype.isNoneType)) (NoneType, envAcc.keys.map(_ -> NoneType).toMap)
            else (TupleType(patTypes), envAcc)
          }
        val (tys, envs) = tyEnvPairs.unzip
        (subtype.join(tys), subtype.joinEnvs(envs))
      case PatNil() =>
        val patType = narrow.meet(t, NilType)
        (patType, env)
      case PatCons(hPat, tPat) =>
        narrow.asListType(t) match {
          case None =>
            val (_, env1) = elabPat(hPat, NoneType, env)
            val (_, env2) = elabPat(tPat, NoneType, env1)
            (NoneType, env2)
          case Some(ListType(elemType)) =>
            val (hType, env1) = elabPat(hPat, elemType, env)
            val (tType, env2) = elabPat(tPat, ListType(elemType), env1)
            narrow.asListType(tType) match {
              case Some(ListType(refinedT)) => (ListType(subtype.join(hType, refinedT)), env2)
              case None                     => (AnyType, env2)
            }
        }
      case PatMatch(p1: PatVar, p2) =>
        val (t2, env1) = elabPat(p2, t, env)
        elabPat(p1, t2, env1)
      case PatMatch(p1, p2) =>
        val (t1, env1) = elabPat(p1, t, env)
        elabPat(p2, t1, env1)
      case unOp: PatUnOp =>
        elabUnOp(unOp, t, env)
      case binOp: PatBinOp =>
        elabBinOp(binOp, t, env)
      case PatBinary(elems) =>
        val patType = narrow.meet(t, BinaryType)
        var envAcc = env
        for { elem <- elems } {
          envAcc = elabBinaryElem(elem, envAcc)
        }
        (patType, envAcc)
      case PatRecordIndex(_, _) =>
        val patType = narrow.meet(t, NumberType)
        (patType, env)
      case PatRecord(recName, namedFields, genFieldOpt) =>
        val recType = narrow.meet(t, RecordType(recName)(module))
        val recDecl = util.getRecord(module, recName).getOrElse(throw UnboundRecord(pat.pos, recName))
        var envAcc = env
        var refinedFields: Map[String, Type] = Map.empty
        for (namedField <- namedFields) {
          val fieldTy = narrow.getRecordField(recDecl, recType, namedField.name)
          val (patType, env1) = elabPat(namedField.pat, fieldTy, envAcc)
          envAcc = env1
          refinedFields += (namedField.name -> patType)
        }
        genFieldOpt match {
          case Some(genField) =>
            val usedNames = namedFields.map(_.name)
            val allNames = recDecl.fields.keySet
            val genNames = (allNames -- usedNames).toList.sorted
            for (genName <- genNames) {
              val fieldTy = narrow.getRecordField(recDecl, recType, genName)
              val (patType, env1) = elabPat(genField, fieldTy, envAcc)
              envAcc = env1
              refinedFields += (genName -> patType)
            }
          case None =>
        }
        if (refinedFields.values.exists(subtype.isNoneType) || subtype.isNoneType(recType))
          (NoneType, envAcc.keys.map(_ -> NoneType).toMap)
        else
          (RefinedRecordType(RecordType(recName)(module), refinedFields), envAcc)
      case PatMap(kvs) =>
        val mapType = narrow.asMapType(t)
        var refinedMapType = mapType
        var envAcc = env
        for ((keyTest, valPat) <- kvs) {
          keyTest match {
            case TestAtom(key) =>
              val (_, env1) = elabPat(valPat, narrow.getValType(key, mapType), envAcc)
              refinedMapType = narrow.withRequiredProp(key, mapType)
              envAcc = env1
            case _ =>
              val env1 = pipelineContext.elabGuard.elabTestT(keyTest, narrow.getKeyType(mapType), envAcc)
              val (_, env2) = elabPat(valPat, narrow.getValType(mapType), env1)
              envAcc = env2
          }
        }
        (refinedMapType, envAcc)
    }
  }

  private def elabBinaryElem(elem: PatBinaryElem, env: Env): Env = {
    for (eSize <- elem.size)
      check.checkExpr(eSize, NumberType, env)
    val isStringLiteral = elem.pat.isInstanceOf[PatString]
    val expType = BinarySpecifiers.expType(elem.specifier, isStringLiteral)
    val (_, env1) = elabPat(elem.pat, expType, env)
    env1
  }

  private def elabUnOp(pat: PatUnOp, t: Type, env: Env): (Type, Env) = {
    val PatUnOp(op, arg) = pat
    op match {
      case "+" | "-" | "bnot" =>
        val (_, env1) = elabPat(arg, NumberType, env)
        (NumberType, env1)
      // $COVERAGE-OFF$
      case _ => throw UnhandledOp(pat.pos, op)
      // $COVERAGE-ON$
    }
  }

  private def elabBinOp(binOp: PatBinOp, t: Type, env: Env): (Type, Env) = {
    val PatBinOp(op, arg1, arg2) = binOp
    op match {
      case "/" | "*" | "-" | "+" | "div" | "rem" | "band" | "bor" | "bxor" | "bsl" | "bsr" =>
        val (_, env1) = elabPat(arg1, NumberType, env)
        val (_, env2) = elabPat(arg2, NumberType, env1)
        (NumberType, env2)
      case "++" =>
        val asListT = narrow.asListType(t).getOrElse(NoneType)
        val (arg1Ty, env1) = elabPat(arg1, asListT, env)
        val (arg2Ty, env2) = elabPat(arg2, asListT, env1)
        (narrow.asListType(arg1Ty), narrow.asListType(arg2Ty)) match {
          case (Some(ListType(elem1Ty)), Some(ListType(elem2Ty))) =>
            (ListType(subtype.join(elem1Ty, elem2Ty)), env2)
          // $COVERAGE-OFF$
          case _ => (NoneType, env2)
          // $COVERAGE-ON$
        }
      case ">" | "<" | "/=" | ">=" | "=<" | "=/=" | "=:=" | "==" =>
        (booleanType, env)
      // $COVERAGE-OFF$
      case _ => throw UnhandledOp(binOp.pos, op)
      // $COVERAGE-ON$
    }
  }
}
