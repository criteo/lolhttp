git checkout master
sbt unidoc
sbt examples/compile -DgenerateExamples
git checkout gh-pages
mkdir doc
rm -rf api
cp -rf target/scala-2.12/unidoc api
cp -rf examples/target/html examples0
rm -rf examples
cp -rf examples0 examples
rm -rf examples0
git commit -a -m "Update documentation"
git push origin gh-pages
