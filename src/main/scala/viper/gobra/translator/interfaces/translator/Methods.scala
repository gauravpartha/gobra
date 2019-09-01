package viper.gobra.translator.interfaces.translator

import viper.gobra.ast.{internal => in}
import viper.gobra.translator.interfaces.Context
import viper.silver.{ast => vpr}
import viper.gobra.translator.util.ViperWriter.MemberWriter


abstract class Methods extends Generator {

  def method(meth: in.Method)(ctx: Context): MemberWriter[vpr.Method]
  def function(func: in.Function)(ctx: Context): MemberWriter[vpr.Method]
}
