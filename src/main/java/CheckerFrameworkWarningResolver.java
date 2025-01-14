import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.ParseResult;
import com.github.javaparser.utils.SourceRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CheckerFrameworkWarningResolver {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java CheckerFrameworkWarningResolver <projectRoot> <warningsFilePath>");
            return;
        }

        String projectRoot = args[0];
        String warningsFilePath = args[1];

        try {
            JavaParser parser = new JavaParser();
            SourceRoot sourceRoot = new SourceRoot(Paths.get(projectRoot));
            List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParse("");

            Pattern warningPattern = Pattern.compile("^.*\\.java:\\d+:\\d+:.*$");
            List<String> warnings = Files.readAllLines(Paths.get(warningsFilePath)).stream()
                    .filter(line -> warningPattern.matcher(line).matches())
                    .collect(Collectors.toList());

            for (String warning : warnings) {
                processWarning(warning, parseResults, projectRoot);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void processWarning(String warning, List<ParseResult<CompilationUnit>> parseResults, String projectRoot) {
        try {
            String[] parts = warning.split(":");
            String fileName = parts[0].trim();
            int lineNumber = Integer.parseInt(parts[1].trim());
            int columnNumber = Integer.parseInt(parts[2].trim());
            String warningType = parts[3].split(" ")[1].trim();

            Path filePath = Paths.get(projectRoot, fileName).normalize();

            for (ParseResult<CompilationUnit> parseResult : parseResults) {
                if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                    CompilationUnit compilationUnit = parseResult.getResult().get();
                    if (compilationUnit.getStorage().isPresent() &&
                            compilationUnit.getStorage().get().getPath().equals(filePath)) {
                        compilationUnit.accept(new VoidVisitorAdapter<Void>() {
                            @Override
                            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                                super.visit(n, arg);
                                String packageName = compilationUnit.getPackageDeclaration()
                                        .map(pd -> pd.getName().toString())
                                        .orElse("");
                                String className = n.getNameAsString();
                                String qualifiedClassName = packageName.isEmpty() ? className : packageName + "." + className;

                                for (BodyDeclaration<?> member : n.getMembers()) {
                                    if ((member instanceof FieldDeclaration || member instanceof MethodDeclaration) &&
                                            member.getBegin().isPresent() &&
                                            member.getEnd().isPresent()) {
                                        int beginLine = member.getBegin().get().line;
                                        int endLine = member.getEnd().get().line;
                                        int beginColumn = member.getBegin().get().column;
                                        int endColumn = member.getEnd().get().column;

                                        if (beginLine <= lineNumber && endLine >= lineNumber) {
                                            if (lineNumber == beginLine && columnNumber < beginColumn) {
                                                continue;
                                            }
                                            if (lineNumber == endLine && columnNumber > endColumn) {
                                                continue;
                                            }
                                            if (member instanceof MethodDeclaration) {
                                                MethodDeclaration method = (MethodDeclaration) member;
                                                String methodName = method.getNameAsString() + "()";
                                                System.out.println(qualifiedClassName + "#" + methodName);
                                            } else if (member instanceof FieldDeclaration) {
                                                FieldDeclaration field = (FieldDeclaration) member;
                                                String fieldName = field.getVariables().get(0).getNameAsString();
                                                System.out.println(qualifiedClassName + "#" + fieldName);
                                            }
                                        }
                                    }
                                }
                            }
                        }, null);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error processing warning: " + warning);
            e.printStackTrace();
        }
    }
}
