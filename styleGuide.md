We generally follow the [official style guide](http://docs.scala-lang.org/style/) and IntelliJ's formatting, but the following are places where we differ or blanks have been filled in. 

**The Hard and Fast Rules**

These are not subject to change.
 - Newline after a closing `}` except for `} else {`
 - Newlines surrounding multi-line functions:
 
         def foo: Int = 5
         def qux: Int = 8
      
         def blah: Int = {
            val x = 7
            val z = 8
            x + z
         }

         def bar: Int = 7
         def baz: Int = 10
 - No newlines between `case` statements:
  
         case Some(x) => doSomethingWith(x)
         case None => tooBad()

 - Space after `,` e.g. in tuples - `(x, y)` not `(x,y)`
 - Space after `:` e.g. in type ascriptions, but **not** before - `foo: Blah` not `foo : Blah`
 - Do not pack multiple statements on a single line with `;`

**Rules Were Meant To Be Broken**

The number one rule is that the overall aesthetic is more important than slavish devotion to this guide. The following are circumstances that we view as the default case however if one believes what they've done is aesthetically superior and the reviewers agree it's AOK.
 - Line length: Try to stay to the left of the 120 character vertical line IntelliJ provides by default. If you approach it view that as a sign you're putting too much stuff on one line. However there are instances where even exceeding that limit ends up being better overall, a lot depends on how important the right hand side of the line is, e.g. `getOrElse(throw new Exception(...))` isn't so meaningful usually so why not
 - Prefer immutability. Constructs like `var` and mutable collections have their place but should be viewed with suspicion. Sometimes they're unavoidable, so be it. When debating between a val mutable collection and a var immutable, generally the latter is preferred.
 - Avoid newlines between single line functions and vals unless you're trying to convey some logical separation or there's some complicated logic
 
        def foo = 8 + 5 
        val blah = "hi"

        def somethingElseEntirely = 42 / 6
 - Ideally higher order functions and the right hand side of case statements are passed a named function and not a closure. This is not for abstraction purposes but for readability.  If I say `foo map { doSomethingInteresting }` I don't have to mentally parse the code doing something interesting to see what it's doing nor do I have to even read it if I trust it. (Trust driven development FTW).  Also named functions are more readily testable so trust doesn't degenerate into blind faith.
 - Closures passed to HOFs and cases should be short and simple. There are always exceptions to this but three lines should be a cue to evaluate if what you're doing could be broken into components.
 - Functions should be kept short and composed of smaller functions. This is held to far less strictly than the anonymous closure rule
 - If a function, case statement, etc consists of a single expression prefer putting it on a single line as long as it's not too long (see the line length entry)
 
        // Yes
        def foo(x: Int) = x * 42

        // No
        def foo(x: Int): Int = {
           x * 42
        }
 - The body of multiline functions, case statements, etc should not share a line with the `def`, `case`, etc
 
        // Yes
        def foo(x: Int): Int = {
          val z = x * 42
          z * 10
        }

        // No
        def foo(x: Int): Int = {val z = x * 42
          z * 10
        }
 - As with the scala style guide we prefer the HOF style of `foo map { ... }`. sometimes that's difficult (e.g. `foo map { ... } toSet`) in which case `foo.map(...)` is acceptable. There are also times when the latter is simply more aesthetically pleasing which is also fine. Use the one which reads better in a particular circumstance but the infix should be used absent a real reason otherwise
 - Expressions in string interpolations should be kept to a minimum and only the most simplistic of logic. Things like `${foo + 5}` should be viewed as the limit, try to keep these to accessors and not perform actual manipulations here
 - Prefer an `andThen` style over a `compose` style, e.g. `foo.doSomething.doSomethingElse.doAnotherThing` over `doAnotherThing(doSomethingElse(doSomething(foo)))`. This allows code to be read as direct chain of transformations on data
  

**Lofty Goals**

It's good to have goals. These are things which are too squishy to even say they're default states but are ideals that should be strived for. It's recognized that these can frequently be infeasible, inpractical or not worth the effort but they're always worth keeping in mind.
 - Type early & type often. When in doubt, make a type, not a `type` alias.  `type` aliases are squishy and can be passed unaliased values.  Drilling into data members of type aliases all too often degenerates into an unreadable mess of `foo._2._1.head` rather than a descriptively named member.
 - Prefer monadic operations: TODO: examples do and don't
 - Prefer referential transparency. The definition of this term turns out to hotly debated, but we go with [this one](http://blog.higher-order.com/blog/2012/09/13/what-purity-is-and-isnt/). This is the author of the FP in Scala book FWIW

**Known Exceptions**

Library-specific DSLs can always lead to deviations from the above and will have to be dealt with on a case by case basis
