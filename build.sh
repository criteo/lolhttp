git checkout master
sbt unidoc
git checkout gh-pages
mkdir doc
rm -rf doc/api
rm -rf doc/examples
cp -rf target/scala-2.12/unidoc doc/api
cp -rf examples/target/html doc/examples
git commit -a -m "New website"
git push gitlab-preprod gh-pages