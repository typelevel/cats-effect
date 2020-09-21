const React = require('react');

const CompLibrary = require('../../core/CompLibrary');

const Container = CompLibrary.Container;

// TODO: include everything from https://github.com/typelevel/cats-effect/issues/1098
function Resources(props) {
  return (
    <div className="docMainWrapper wrapper">
      <Container className="mainContainer versionsContainer">
        <div className="post">
          <header className="postHeader">
            <h1>Resources</h1>
          </header>

          <p>
            There are many third-party resources past this documentation site dedicated to teaching Scala developers about Cats Effect.
          </p>

          <h2>Talks</h2>

          <h2>Training</h2>

          <h2>Books</h2>

          <h2>Blog posts</h2>

          <h2>Gists</h2>

          <h2>Gitter</h2>

        </div>
      </Container>
    </div>
  );
}

module.exports = Resources;
