package pkg

func foo() {
  // ghost error
  //:: ExpectedOutput(type_error)
  xs := seq[int] { }
}
